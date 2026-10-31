package net.i2p.i2ptunnel.irc;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.I2PTunnelRunner;
import net.i2p.i2ptunnel.I2PTunnelServer;
import net.i2p.i2ptunnel.Logging;
import net.i2p.util.EventDispatcher;

/**
 * A standard server that only answers for registered ports,
 * and each port can only be used once.
 * <pre>
 *
 *                                            direct conn
 *                &lt;---&gt; I2PTunnelDCCServer &lt;---------------&gt;I2PTunnelDCCClient &lt;----&gt;
 *   originating                                                                     responding
 *   chat client                                                                     chat client
 *        CHAT    ---&gt; I2PTunnelIRCClient --&gt; IRC server --&gt; I2TunnelIRCClient -----&gt;
 *        SEND    ---&gt; I2PTunnelIRCClient --&gt; IRC server --&gt; I2TunnelIRCClient -----&gt;
 *        RESUME  &lt;--- I2PTunnelIRCClient &lt;-- IRC server &lt;-- I2TunnelIRCClient &lt;-----
 *        ACCEPT  ---&gt; I2PTunnelIRCClient --&gt; IRC server --&gt; I2TunnelIRCClient -----&gt;
 * </pre>
 *
 * @since 0.8.9
 */
public class I2PTunnelDCCServer extends I2PTunnelServer {

    /** Key is the server's local I2P port. */
    private final ConcurrentHashMap<Integer, LocalAddress> _outgoing;
    /** Key is the server's local I2P port. */
    private final ConcurrentHashMap<Integer, LocalAddress> _active;
    /** Key is the server's local I2P port. */
    private final ConcurrentHashMap<Integer, LocalAddress> _resume;
    /** The open DCC sockets. */
    private final List<I2PSocket> _sockList;

    /** Just to keep super() happy. */
    private static final InetAddress DUMMY;
    static {
        InetAddress dummy = null;
        try {
            dummy = InetAddress.getByAddress(new byte[4]);
        } catch (UnknownHostException uhe) { /* ignored */ }
        DUMMY = dummy;
    }

    /** Smallest DCC port accepted. */
    private static final int MIN_I2P_PORT = 1;
    /** Largest DCC port accepted. */
    private static final int MAX_I2P_PORT = 65535;
    /** Outgoing DCC connections allowed pending. */
    private static final int MAX_OUTGOING_PENDING = 20;
    /** Outgoing DCC connections allowed open. */
    private static final int MAX_OUTGOING_ACTIVE = 20;
    /** Time before an unused local address expires. */
    private static final long OUTBOUND_EXPIRE = 30*(long) 60*1000;
    /** Default idle time before a DCC socket is dropped. */
    private static final long DEFAULT_DCC_READ_TIMEOUT = -1;

    /**
     * There's no support for unsolicited incoming I2P connections,
     * so there's no server host or port parameters.
     *
     * @param sktMgr an existing socket manager
     * @param l the logging system the base class reports through
     * @param notifyThis the dispatcher notified of connection and transfer events
     * @param tunnel the owning I2PTunnel supplying the router context and configuration
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */
    public I2PTunnelDCCServer(I2PSocketManager sktMgr, Logging l,
                              EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(DUMMY, 0, sktMgr, l, notifyThis, tunnel);
        _outgoing = new ConcurrentHashMap<>(8);
        _active = new ConcurrentHashMap<>(8);
        _resume = new ConcurrentHashMap<>(8);
        _sockList = new CopyOnWriteArrayList<>();
        readTimeout = DEFAULT_DCC_READ_TIMEOUT;
    }

    /**
     * An incoming DCC connection, only accept for a known port.
     * Passed through without filtering.
     *
     * @param socket the incoming I2P socket connection
     * @since 0.8.9
     */
    @Override
    protected void blockingHandle(I2PSocket socket) {
        if (_log.shouldInfo())
            _log.info("Incoming connection to " + toString() +
                       "\n* From: [" + socket.getPeerDestination().calculateHash().toBase32().substring(0, 8) + "]");

        try {
            expireOutbound();
            int myPort = socket.getLocalPort();
            // Port is a one-time-use only
            LocalAddress local = _outgoing.remove(Integer.valueOf(myPort));
            if (local == null) {
                if (_log.shouldWarn())
                    _log.warn("Rejecting incoming DCC connection for unknown port " + myPort);
                try {
                    socket.close();
                } catch (IOException ioe) { /* ignored */ }
                return;
            }
            if (_log.shouldWarn())
                _log.warn("Incoming DCC connection for I2P port " + myPort +
                          " sending to " + local.ia + ':' + local.port);
            try {
                Socket s = new Socket(local.ia, local.port);
                _sockList.add(socket);
                I2PTunnelRunner runner = new I2PTunnelRunner(s, socket, slock, null, null, _sockList,
                                               (I2PTunnelRunner.FailCallback) null);
                // run in the unlimited client pool
                //t.start();
                runner.setExecutor(_clientExecutor);
                _clientExecutor.execute(runner);
                local.socket = socket;
                local.expire = getTunnel().getContext().clock().now() + OUTBOUND_EXPIRE;
                _active.put(Integer.valueOf(myPort), local);
            } catch (SocketException ex) {
                try {
                    socket.reset();
                } catch (IOException ioe) { /* ignored */ }
                _log.error("Error relaying incoming DCC connection to IRC client at " + local.ia + ':' + local.port, ex);
            }
        } catch (IOException ex) {
            // Non-SocketException failures (e.g. InterruptedIOException while the
            // IRC client is shutting down) must not leave the peer's accepted
            // I2P socket open with no relay and no close.
            try {
                socket.close();
            } catch (IOException ioe) { /* ignored */ }
            _log.error("Error while waiting for I2PConnections", ex);
        }
    }

    /**
     * Closes all connections and clears all pending/outgoing/active maps.
     *
     * @param forced unused, for interface compatibility
     * @return true if closed successfully
     * @since 0.8.9
     */
    @Override
    public boolean close(boolean forced) {
        _outgoing.clear();
        _active.clear();
        _resume.clear();
        for (I2PSocket s : _sockList) {
            try {
                s.close();
            } catch (IOException ioe) { /* ignored */ }
        }
        _sockList.clear();
        return super.close(forced);
    }

    /**
     * An outgoing DCC request.
     *
     * @param ip local irc client IP
     * @param port local irc client port
     * @param type ignored; the overload always lets the router pick the I2P port
     * @return i2p port or -1 on error
     */
    public int newOutgoing(byte[] ip, int port, String type) {
        return newOutgoing(ip, port, 0);
    }

    /**
     * Register an outgoing DCC session, returning its id.
     *
     * @param ip local irc client IP
     * @param port local dcc server I2P port or 0 to pick one at random
     * @param i2pPort the I2P port to reserve, or 0 to pick an unused one at random
     * @return the reserved I2P port, or -1 on error
     */
    private int newOutgoing(byte[] ip, int port, int i2pPort) {
        expireOutbound();
        if (_outgoing.size() >= MAX_OUTGOING_PENDING ||
            _active.size() >= MAX_OUTGOING_ACTIVE) {
            _log.error("Too many outgoing DCC, max is " + MAX_OUTGOING_PENDING +
                       '/' + MAX_OUTGOING_ACTIVE + " pending/active");
            return -1;
        }
        InetAddress ia;
        try {
            ia = InetAddress.getByAddress(ip);
        } catch (UnknownHostException uhe) {
            return -1;
        }
        int limit = i2pPort > 0 ? 10 : 1;
        expireOutbound();
        LocalAddress client = new LocalAddress(ia, port, getTunnel().getContext().clock().now() + OUTBOUND_EXPIRE);
        for (int i = 0; i < limit; i++) {
            int iport;
            if (i2pPort > 0)
                iport = i2pPort;
            else
                iport = MIN_I2P_PORT + getTunnel().getContext().random().nextInt(1 + MAX_I2P_PORT - MIN_I2P_PORT);
            if (_active.containsKey(Integer.valueOf(iport)))
                continue;
            LocalAddress old = _outgoing.putIfAbsent(Integer.valueOf(iport), client);
            if (old != null)
                continue;
            return iport;
        }
        // couldn't find an unused i2p port
        return -1;
    }

    /**
     * An incoming RESUME request.
     *
     * @param port local dcc server I2P port
     * @return local IRC client DCC port or -1 on error
     */
    public int resumeIncoming(int port) {
        Integer iport = Integer.valueOf(port);
        LocalAddress local = _active.remove(iport);
        if (local != null) {
            local.expire = getTunnel().getContext().clock().now() + OUTBOUND_EXPIRE;
            _resume.put(Integer.valueOf(local.port), local);
            return local.port;
        }
        local = _outgoing.get(iport);
        if (local != null) {
            // shouldn't happen
            local.expire = getTunnel().getContext().clock().now() + OUTBOUND_EXPIRE;
            return local.port;
        }
        return -1;
    }

    /**
     * An outgoing ACCEPT response.
     *
     * @param port local irc client DCC port
     * @return local DCC server i2p port or -1 on error
     */
    public int acceptOutgoing(int port) {
        // do a reverse lookup
        for (Iterator<Map.Entry<Integer, LocalAddress>> iter = _resume.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry<Integer, LocalAddress> e = iter.next();
            LocalAddress local = e.getValue();
            if (local.port == port) {
                iter.remove();
                return newOutgoing(local.ia.getAddress(), port, e.getKey().intValue());
            }
        }
        return -1;
    }

    /** Drops the expired outgoing DCC connections, including unanswered RESUME reservations. */
    private void expireOutbound() {
        for (Iterator<LocalAddress> iter = _outgoing.values().iterator(); iter.hasNext(); ) {
            LocalAddress a = iter.next();
            if (a.expire < getTunnel().getContext().clock().now())
                iter.remove();
        }
        // A RESUME whose peer never sends ACCEPT would otherwise sit in _resume
        // for the tunnel's lifetime — unbounded growth outside the MAX_OUTGOING caps.
        for (Iterator<LocalAddress> iter = _resume.values().iterator(); iter.hasNext(); ) {
            LocalAddress a = iter.next();
            if (a.expire < getTunnel().getContext().clock().now())
                iter.remove();
        }
        for (Iterator<LocalAddress> iter = _active.values().iterator(); iter.hasNext(); ) {
            LocalAddress a = iter.next();
            I2PSocket s = a.socket;
            if (s != null && s.isClosed())
                iter.remove();
        }
    }

    /** One local address handed out for a DCC connection. */
    private static class LocalAddress {
        /** The fake address sent to the peer. */
        public final InetAddress ia;
        /** The fake port sent to the peer. */
        public final int port;
        /** When the address expires if unused. */
        public long expire;
        /** The socket connected to the address, once opened. */
        public I2PSocket socket;

        /**
         * Records one local address.
         *
         * @param a the fake address sent to the peer
         * @param p the fake port sent to the peer
         * @param exp when the address expires if unused
         */
        public LocalAddress(InetAddress a, int p, long exp) {
            ia = a;
            port = p;
            expire = exp;
        }
    }
}
