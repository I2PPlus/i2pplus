package net.i2p.client.impl;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionListener;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.util.Log;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * public domain
 */

/**
 * Implement multiplexing with a 1-byte 'protocol' and a two-byte 'port'.
 * Listeners register with either {@link #addListener} or addMuxedListener(),
 * depending on whether they want to hear about the
 * protocol, from port, and to port for every received message.
 * <p>
 * {@link #messageAvailable} only calls one listener, not all that apply.
 * The others call all listeners.
 *
 * @since 0.7.1
 */
public class I2PSessionDemultiplexer implements I2PSessionMuxedListener {
    /** Logger for this class. */
    private final Log _log;
    /** Muxed listeners, by protocol and port. */
    private final Map<Integer, I2PSessionMuxedListener> _listeners;

    /**
     * Create a new demultiplexer.
     *
     * @param ctx supplies the log
     */
    public I2PSessionDemultiplexer(I2PAppContext ctx) {
        _log = ctx.logManager().getLog(I2PSessionDemultiplexer.class);
        _listeners = new ConcurrentHashMap<>(4);
    }

    /**
     * Intentional no-op. Notifications reach a registered listener through the
     * four-argument overload below, which knows the protocol and ports to match
     * on. A muxed session never calls this one, because its availability
     * notifier is the MuxedAvailabilityNotifier.
     *
     * @param session session to notify
     * @param msgId message number available
     * @param size size of the message payload in bytes
     */
    @Override
    public void messageAvailable(I2PSession session, int msgId, long size) {
    }

    /**
     * Notify the best-matching listener for the protocol and port.
     *
     * @param session session to notify
     * @param msgId message number available
     * @param size size of the message payload in bytes
     * @param proto 1-254 or 0 for unspecified
     * @param fromport 1-65535 or 0 for unspecified
     * @param toport 1-65535 or 0 for unspecified
     */
    @Override
    public void messageAvailable(I2PSession session, int msgId, long size, int proto, int fromport, int toport) {
        I2PSessionMuxedListener l = findListener(proto, toport);
        if (l != null) {
            l.messageAvailable(session, msgId, size, proto, fromport, toport);
        } else {
            // no listener, throw it out
            if (_log.shouldDebug()) {
                if (_listeners.isEmpty()) {
                    _log.debug("No listeners for incoming message");
                } else {
                    _log.debug("No listener for proto " + proto +
                               " fromport " + fromport + " toport " + toport +
                               " msg id " + msgId + " of " +
                               _listeners.size() + " listeners");
                }
                try {
                    byte[] msg = session.receiveMessage(msgId);
                    _log.debug("Message:\n" + net.i2p.util.HexDump.dump(msg));
                } catch (I2PSessionException ise) { /* diagnostic dump only; the message was already undeliverable */ }
            } else {
                session.discardMessage(msgId);
            }
        }
    }

    /**
     * Convert a protocol number to a human-readable string.
     *
     * @param proto an {@link net.i2p.client.I2PSession} protocol number, or 0
     *              for any
     * @return human-readable protocol name, or null if unrecognised
     */
    public static String protocolNumberToString(int proto) {
        switch (proto) {
            case 0: return "ANY (0)";
            case 6: return "STREAMING (6)";
            case 17: return "DATAGRAM1 (17)";
            case 18: return "DATAGRAM_RAW (18)";
            case 19: return "DATAGRAM2 (19)";
            case 20: return "DATAGRAM3 (20)";
            default: return "Unknown (" + proto + ")";
        }
    }

    @Override
    public void reportAbuse(I2PSession session, int severity) {
        Collection<I2PSessionMuxedListener> lsnrs = _listeners.values();
        // dedup
        if (lsnrs.size() > 1) lsnrs = new HashSet<>(lsnrs);
        for (I2PSessionMuxedListener l : lsnrs) {
            l.reportAbuse(session, severity);
        }
    }

    @Override
    public void disconnected(I2PSession session) {
        Collection<I2PSessionMuxedListener> lsnrs = _listeners.values();
        // dedup
        if (lsnrs.size() > 1) lsnrs = new HashSet<>(lsnrs);
        for (I2PSessionMuxedListener l : lsnrs) {
            if (_log.shouldInfo()) _log.info("Sending disconnected() to " + l);
            l.disconnected(session);
        }
    }

    @Override
    public void errorOccurred(I2PSession session, String message, Throwable error) {
        Collection<I2PSessionMuxedListener> lsnrs = _listeners.values();
        // dedup
        if (lsnrs.size() > 1) lsnrs = new HashSet<>(lsnrs);
        for (I2PSessionMuxedListener l : lsnrs) {
            if (_log.shouldInfo()) _log.info("Sending session error \"" + message + "\" to " + l);
            l.errorOccurred(session, message, error);
        }
    }

    /**
     * For those that don't need to hear about the protocol and ports
     * in messageAvailable()
     * (Streaming lib).
     *
     * @param l the listener
     * @param proto the protocol number
     * @param port the port number
     */
    public void addListener(I2PSessionListener l, int proto, int port) {
        if (proto < 0 || proto > 254 || port < 0 || port > 65535) throw new IllegalArgumentException();
        if (_log.shouldInfo()) _log.info("Adding a listener that does not see ports: " + l + ' ' + proto + ' ' + port);
        I2PSessionListener old = _listeners.put(key(proto, port), new NoPortsListener(l));
        if (old != null && _log.shouldWarn()) _log.warn("Listener " + l + " replaces " + old + " for proto: " + proto + " port: " + port);
    }

    /**
     * For those that do care
     * UDP perhaps.
     *
     * @param l the listener
     * @param proto the protocol number
     * @param port the port number
     */
    public void addMuxedListener(I2PSessionMuxedListener l, int proto, int port) {
        if (proto < 0 || proto > 254 || port < 0 || port > 65535) throw new IllegalArgumentException();
        if (_log.shouldInfo()) _log.info("Adding a listener: " + l + ' ' + proto + ' ' + port);
        I2PSessionListener old = _listeners.put(key(proto, port), l);
        if (old != null && _log.shouldWarn()) _log.warn("Listener " + l + " replaces " + old + " for proto: " + proto + " port: " + port);
    }

    /**
     * Remove a listener for the given protocol and port.
     *
     * @param proto the protocol number
     * @param port the port number
     */
    public void removeListener(int proto, int port) {
        if (proto < 0 || proto > 254 || port < 0 || port > 65535) throw new IllegalArgumentException();
        if (_log.shouldInfo()) _log.info("Removing listener for proto: " + proto + " port: " + port);
        _listeners.remove(key(proto, port));
    }

    /**
     * Find the one listener that most specifically matches the request.
     *
     * @param proto the protocol number
     * @param port the port number
     * @return the best-matching listener, or null if none matches
     */
    private I2PSessionMuxedListener findListener(int proto, int port) {
        I2PSessionMuxedListener rv = getListener(proto, port);
        if (rv != null) return rv;
        if (port != I2PSession.PORT_ANY) { // try any port
            rv = getListener(proto, I2PSession.PORT_ANY);
            if (rv != null) return rv;
        }
        if (proto != I2PSession.PROTO_ANY) { // try any protocol
            rv = getListener(I2PSession.PROTO_ANY, port);
            if (rv != null) return rv;
        }
        if (proto != I2PSession.PROTO_ANY && port != I2PSession.PORT_ANY) { // try default
            rv = getListener(I2PSession.PROTO_ANY, I2PSession.PORT_ANY);
        }
        return rv;
    }

    /**
     * Finds the listener for a protocol and port pair.
     *
     * @param proto the protocol number
     * @param port the port number
     * @return the listener, null if none is registered
     */
    private I2PSessionMuxedListener getListener(int proto, int port) {
        return _listeners.get(key(proto, port));
    }

    /**
     * Combines a protocol and port into one map key.
     *
     * @param proto the protocol number
     * @param port the port number
     * @return the key
     */
    private static Integer key(int proto, int port) {
        return Integer.valueOf(((port << 8) & 0xffff00) | proto);
    }

    /** For listeners that don't care about proto and ports. */
    private static class NoPortsListener implements I2PSessionMuxedListener {
        /** The listener messages are passed to. */
        private I2PSessionListener _l;

        /**
         * Wraps a listener that has no muxed ports.
         *
         * @param l the listener to wrap
         */
        public NoPortsListener(I2PSessionListener l) {
            _l = l;
        }

        @Override
        public void messageAvailable(I2PSession session, int msgId, long size) {
            throw new IllegalArgumentException("no");
        }

        @Override
        public void messageAvailable(I2PSession session, int msgId, long size, int proto, int fromport, int toport) {
            _l.messageAvailable(session, msgId, size);
        }

        @Override
        public void reportAbuse(I2PSession session, int severity) {
            _l.reportAbuse(session, severity);
        }

        @Override
        public void disconnected(I2PSession session) {
            _l.disconnected(session);
        }

        @Override
        public void errorOccurred(I2PSession session, String message, Throwable error) {
            _l.errorOccurred(session, message, error);
        }
    }
}
