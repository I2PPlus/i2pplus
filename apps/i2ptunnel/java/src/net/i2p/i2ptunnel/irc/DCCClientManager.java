package net.i2p.i2ptunnel.irc;

import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.data.Base32;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.Logging;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

/**
 *  Start, track, and expire the I2PTunnelDCCClients.
 *
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
 *
 * </pre>
 *
 * @since 0.8.9
 */
public class DCCClientManager extends EventReceiver {
    private final I2PSocketManager sockMgr;
    private final EventDispatcher _dispatch;
    private final Logging l;
    private final I2PTunnel _tunnel;
    private final Log _log;

    /** key is the DCC client's local port */
    private final ConcurrentHashMap<Integer, I2PTunnelDCCClient> _incoming;
    /** key is the DCC client's local port */
    private final ConcurrentHashMap<Integer, I2PTunnelDCCClient> _active;
    /** key is the DCC client's local port */
    private final ConcurrentHashMap<Integer, I2PTunnelDCCClient> _complete;

    // list of client tunnels?
    private static long _id;

    private static final int MAX_INCOMING_PENDING = 10;
    private static final int MAX_INCOMING_ACTIVE = 10;
    /**
     * Create a new manager for DCC client tunnels.
     *
     * @param sktMgr the socket manager for creating connections
     * @param logging the logging facility
     * @param dispatch the event dispatcher for tunnel events
     * @param tunnel the parent I2PTunnel instance
     * @since 0.8.9
     */
    public DCCClientManager(I2PSocketManager sktMgr, Logging logging,
                            EventDispatcher dispatch, I2PTunnel tunnel) {
        sockMgr = sktMgr;
        l = logging;
        _dispatch = dispatch;
        _tunnel = tunnel;
        _log = tunnel.getContext().logManager().getLog(DCCClientManager.class);
        _incoming = new ConcurrentHashMap<Integer, I2PTunnelDCCClient>(8);
        _active = new ConcurrentHashMap<Integer, I2PTunnelDCCClient>(8);
        _complete = new ConcurrentHashMap<Integer, I2PTunnelDCCClient>(8);
    }

    /**
     * Close all DCC client tunnels managed by this manager.
     * Stops all incoming, active, and complete client tunnels and clears the internal maps.
     *
     * @param forced unused parameter, retained for compatibility
     * @return true always, indicating all tunnels were closed
     * @since 0.8.9
     */
    public boolean close(boolean forced) {
        for (I2PTunnelDCCClient c : _incoming.values()) {
            c.stop();
        }
        _incoming.clear();
        for (I2PTunnelDCCClient c : _active.values()) {
            c.stop();
        }
        _active.clear();
        _complete.clear();
        return true;
    }

    /**
     * Handle an incoming DCC request from a remote peer.
     * Validates the base32 address and creates a new DCC client tunnel.
     *
     * @param b32 the remote DCC server's base32 destination (60 chars, ending in .b32.i2p)
     * @param port the remote DCC server's I2P port
     * @param type the DCC type (currently ignored)
     * @return the local DCC client tunnel port, or -1 on error (invalid address or too many connections)
     * @since 0.8.9
     */
    public int newIncoming(String b32, int port, String type) {
        return newIncoming(b32, port, type, 0);
    }

    /**
     *  @param localPort bind to port or 0; if nonzero it will be the rv
     */
    private int newIncoming(String b32, int port, String type, int localPort) {
        b32 = b32.toLowerCase(Locale.US);
        // do some basic verification before starting the client
        if (b32.length() != 60 || !b32.endsWith(".b32.i2p"))
            return -1;
        byte[] dec = Base32.decode(b32.substring(0, 52));
        if (dec == null || dec.length != 32)
            return -1;
        expireInbound();
        if (_incoming.size() >= MAX_INCOMING_PENDING ||
            _active.size() >= MAX_INCOMING_PENDING) {
            _log.error("Too many incoming DCC, max is " + MAX_INCOMING_PENDING +
                       '/' + MAX_INCOMING_ACTIVE + " pending/active");
            return -1;
        }
        try {
            // Transparent tunnel used for all types...
            // Do we need to do any filtering for chat?
            I2PTunnelDCCClient cTunnel = new I2PTunnelDCCClient(b32, localPort, port, l, sockMgr,
                                                                _dispatch, _tunnel, ++_id);
            cTunnel.attachEventDispatcher(this);
            cTunnel.startRunning();
            int lport = cTunnel.getLocalPort();
            if (_log.shouldWarn())
                _log.warn("Opened client tunnel at port " + lport +
                          " pointing to " + b32 + ':' + port);
            _incoming.put(Integer.valueOf(lport), cTunnel);
            return lport;
        } catch (IllegalArgumentException uhe) {
            l.log("Could not find listen host to bind to [" + _tunnel.host + "]");
            _log.error("Error finding host to bind", uhe);
            return -1;
        }
    }

    /**
     * Handle an outgoing DCC RESUME request.
     * Stops the existing client tunnel for the given local port.
     *
     * @param port the local DCC client tunnel port to resume
     * @return the local port of the stopped tunnel, or -1 if no matching tunnel was found
     * @since 0.8.9
     */
    public int resumeOutgoing(int port) {
        Integer lport = Integer.valueOf(port);
        I2PTunnelDCCClient tun = _complete.get(lport);
        if (tun == null) {
            tun = _active.get(lport);
            if (tun == null)
                // shouldn't happen
                tun = _incoming.get(lport);
        }
        if (tun != null) {
            tun.stop();
            return tun.getLocalPort();
        }
        return -1;
    }

    /**
     * Handle an incoming DCC ACCEPT response from the remote peer.
     * Searches for a matching tunnel in complete, active, or incoming states
     * and creates a new incoming connection with the original parameters.
     *
     * @param port the remote DCC server's I2P port from the ACCEPT message
     * @return the local DCC client tunnel port for the new connection, or -1 if no match found
     * @since 0.8.9
     */
    public int acceptIncoming(int port) {
        // do a reverse lookup
        for (I2PTunnelDCCClient tun : _complete.values()) {
            if (tun.getRemotePort() == port)
                return newIncoming(tun.getDest(), port, "ACCEPT", tun.getLocalPort());
        }
        for (I2PTunnelDCCClient tun : _active.values()) {
            if (tun.getRemotePort() == port)
                return newIncoming(tun.getDest(), port, "ACCEPT", tun.getLocalPort());
        }
        for (I2PTunnelDCCClient tun : _incoming.values()) {
            if (tun.getRemotePort() == port) {
                // shouldn't happen
                tun.stop();
                return newIncoming(tun.getDest(), port, "ACCEPT", tun.getLocalPort());
            }
        }
        return -1;
    }

    /**
     * EventReceiver callback for DCC client connection events.
     * Handles connection start and stop events to update tunnel state.
     *
     * @param eventName the event name (CONNECT_START_EVENT or CONNECT_STOP_EVENT)
     * @param args the event arguments (I2PTunnelDCCClient for start, Integer port for stop)
     * @since 0.8.9
     */
    public void notifyEvent(String eventName, Object args) {
        if (eventName.equals(I2PTunnelDCCClient.CONNECT_START_EVENT)) {
            try {
                I2PTunnelDCCClient client = (I2PTunnelDCCClient) args;
                connStarted(client);
            } catch (ClassCastException cce) {}
        } else if (eventName.equals(I2PTunnelDCCClient.CONNECT_STOP_EVENT)) {
            try {
                Integer port = (Integer) args;
                connStopped(port);
            } catch (ClassCastException cce) {}
        }
    }

    /**
     * Called when a DCC client connection has started successfully.
     * Moves the tunnel from incoming to active state.
     *
     * @param client the DCC client tunnel that has started
     * @since 0.8.9
     */
    private void connStarted(I2PTunnelDCCClient client) {
        Integer lport = Integer.valueOf(client.getLocalPort());
        I2PTunnelDCCClient c = _incoming.remove(lport);
        if (c != null) {
            _active.put(lport, client);
            if (_log.shouldWarn())
                _log.warn("Added client tunnel for port " + lport +
                          " pending count now: " + _incoming.size() +
                          " active count now: " + _active.size() +
                          " complete count now: " + _complete.size());
        }
    }

    /**
     * Called when a DCC client connection has stopped.
     * Moves the tunnel from incoming or active to complete state.
     *
     * @param lport the local port of the tunnel that stopped
     * @since 0.8.9
     */
    private void connStopped(Integer lport) {
        I2PTunnelDCCClient tun = _incoming.remove(lport);
        if (tun != null)
            _complete.put(lport, tun);
        tun = _active.remove(lport);
        if (tun != null)
            _complete.put(lport, tun);
        if (_log.shouldWarn())
            _log.warn("Removed client tunnel for port " + lport +
                      " pending count now: " + _incoming.size() +
                      " active count now: " + _active.size() +
                      " complete count now: " + _complete.size());
    }

    /**
     * Expires timed-out inbound and complete DCC client tunnels.
     * Removes and stops any tunnels whose expiration time has passed.
     *
     * @since 0.8.9
     */
    private void expireInbound() {
        for (Iterator<I2PTunnelDCCClient> iter = _incoming.values().iterator(); iter.hasNext(); ) {
            I2PTunnelDCCClient c = iter.next();
            if (c.getExpires() < _tunnel.getContext().clock().now()) {
                iter.remove();
                c.stop();
            }
        }
        // shouldn't need to expire active
        for (Iterator<I2PTunnelDCCClient> iter = _complete.values().iterator(); iter.hasNext(); ) {
            I2PTunnelDCCClient c = iter.next();
            if (c.getExpires() < _tunnel.getContext().clock().now()) {
                iter.remove();
                c.stop();
            }
        }
    }
}
