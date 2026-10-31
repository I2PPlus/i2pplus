package net.i2p.client.impl;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionListener;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.client.SendMessageOptions;
import net.i2p.client.SendMessageStatusListener;
import net.i2p.client.TunnelRemovalEvent;
import net.i2p.client.TunnelStatusListener;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.i2cp.MessageId;
import net.i2p.util.SimpleTimer2;

/**
 * Thread safe implementation of an I2P session running over TCP.
 * <p>
 * Unused directly, see {@link I2PSessionMuxedImpl} extension.
 *
 */
class I2PSessionImpl2 extends I2PSessionImpl {

    /** One MessageState object per message in the process of being sent. */
    protected final Map<Long, MessageState> _sendingStates;
    /**
     * Source of the per-message replay nonces sent to the router.
     */
    protected final AtomicLong _sendMessageNonce;
    /** Max # seconds to wait for confirmation of the message send. */
    private final static long SEND_TIMEOUT = (long) 60 * 1000; // 60 seconds to send
    /** Should we gzip each payload prior to sending it? */
    private final static boolean SHOULD_COMPRESS = true;
    /** Whether the router may send compressed lease sets. */
    private final static boolean SHOULD_DECOMPRESS = true;
    /**
     * Don't expect any MSMs from the router for outbound traffic.
     *
     * @since 0.8.1
     */
    protected boolean _noEffort;
    /** How often expired leases are dropped - every 63 seconds. */
    private static final long REMOVE_EXPIRED_TIME = (long) 63*1000;
    /** Reflected accessor for the tunnel manager. */
    private static Method _tunnelManagerMethod;

    /**
     * For extension by SimpleSession (no dest).
     *
     * @param context the app context whose router client the session sends through
     * @param options router connection options, or null to use the System properties
     * @param handlerMap the map the session registers its incoming-message handlers in
     */
    protected I2PSessionImpl2(I2PAppContext context, Properties options,
                              I2PClientMessageHandlerMap handlerMap) {
        super(context, options, handlerMap);
        _sendingStates = null;
        _sendMessageNonce = null;
    }

    /**
     * For extension by I2PSessionMuxedImpl
     * <p>
     * Create a new session, reading the Destination, PrivateKey, and SigningPrivateKey
     * from the destKeyStream, and using the specified options to connect to the router.
     *
     * @param ctx the app context whose router client the session sends through
     * @param destKeyStream stream containing the private key data, format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @param options set of options to configure the router with, if null will use System properties
     * @throws I2PSessionException if there is a problem loading the private keys
     */
    protected I2PSessionImpl2(I2PAppContext ctx, InputStream destKeyStream, Properties options) throws I2PSessionException {
        super(ctx, destKeyStream, options);
        _sendingStates = new ConcurrentHashMap<>(32);
        _sendMessageNonce = new AtomicLong();
        // default is BestEffort
        _noEffort = "none".equals(getOptions().getProperty(I2PClient.PROP_RELIABILITY, "").toLowerCase(Locale.US));
    }

    /**
     * For extension by {@link SubSession} via I2PSessionMuxedImpl
     * <p>
     * Create a new session, reading the Destination, PrivateKey, and SigningPrivateKey
     * from the destKeyStream, and using the specified options to connect to the router.
     *
     * @param primary the muxed session whose context, handlers and producer are shared
     * @param destKeyStream stream containing the private key data, format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @param options set of options to configure the router with, if null will use System properties
     * @throws I2PSessionException if the destination key stream cannot be read or parsed
     * @since 0.9.21
     */
    protected I2PSessionImpl2(I2PSessionImpl primary, InputStream destKeyStream, Properties options) throws I2PSessionException {
        super(primary, destKeyStream, options);
        _sendingStates = new ConcurrentHashMap<>(32);
        _sendMessageNonce = new AtomicLong();
        _noEffort = "none".equals(getOptions().getProperty(I2PClient.PROP_RELIABILITY, "").toLowerCase(Locale.US));
    }

    /**
     * Fire up a periodic task to check for unclaimed messages.
     *
     * @since 0.9.14
     */
    @Override
    protected void startVerifyUsage() {
        super.startVerifyUsage();
        new RemoveExpired();
    }

    /**
     * Check for expired message states, without wastefully setting a timer for each
     * message.
     *
     * @since 0.9.14
     */
    private class RemoveExpired extends SimpleTimer2.TimedEvent {

        /**
         * Register the expiry sweep on the shared timer.
         */
        public RemoveExpired() {super(_context.simpleTimer2(), REMOVE_EXPIRED_TIME);}

        /**
         * Remove expired message states and reschedule.
         */
        @Override
        public void timeReached() {
            if (isClosed()) {return;}
            if (!_sendingStates.isEmpty()) {
                long now = _context.clock().now();
                for (Iterator<MessageState> iter = _sendingStates.values().iterator(); iter.hasNext();) {
                    MessageState state = iter.next();
                    if (state.getExpires() < now) {iter.remove();}
                }
            }
            schedule(REMOVE_EXPIRED_TIME);
        }
    }

    /**
     * Wait this long for the router to accept a sent message, in ms.
     * @return milliseconds to wait for the router to accept the message
     */
    protected long getTimeout() {return SEND_TIMEOUT;}

    /**
     * Destroy the I2P session, after clearing the message send states.
     *
     * @param sendDisconnect if true, send a disconnect message to the router
     */
    @Override
    public void destroySession(boolean sendDisconnect) {
        clearStates();
        super.destroySession(sendDisconnect);
    }

    /**
     * Don't bother if really small. Three 66-byte messages will fit in one tunnel message.
     * Four messages don't fit no matter how small. So below 66 it isn't worth it.
     * See ConnectionOptions.java in the streaming lib for similar calculations.
     * <p>
     * Since we still have to pass it through gzip -0 the CPU savings is trivial but it's
     * the best we can do for now. See below. {@code i2cp.gzip} defaults to SHOULD_COMPRESS = true.
     * <p>
     * Perhaps the http server (which does its own compression) and P2P apps
     * (with generally uncompressible data) should set to false.
     */

    private static final int DONT_COMPRESS_SIZE = 66; // Todo: don't compress if destination is local?

    /**
     * Return whether compression should be used.
     *
     * @param size the uncompressed message size in bytes, compared to 66
     * @return true if the message should be compressed before sending
     */
    protected boolean shouldCompress(int size) {
        if (size <= DONT_COMPRESS_SIZE) {return false;}
        String p = getOptions().getProperty(I2PClient.PROP_GZIP);
        if (p != null) {return Boolean.parseBoolean(p);}
        return SHOULD_COMPRESS;
    }

    /**
     * Register a session listener.
     * Not supported by this implementation - use I2PSessionMuxedImpl,
     * which demultiplexes by protocol and port.
     *
     * @param lsnr the listener
     * @param proto ignored - this implementation registers no listeners
     * @param port ignored - this implementation registers no listeners
     * @throws UnsupportedOperationException always, as listeners are unsupported here
     */
    @Override
    public void addSessionListener(I2PSessionListener lsnr, int proto, int port) {
        throw new UnsupportedOperationException("Use MuxedImpl");
    }
    /**
     * Register a muxed session listener.
     * Not supported by this implementation - use I2PSessionMuxedImpl.
     *
     * @param l the listener
     * @param proto ignored - this implementation registers no listeners
     * @param port ignored - this implementation registers no listeners
     * @throws UnsupportedOperationException always, as listeners are unsupported here
     */
    @Override
    public void addMuxedSessionListener(I2PSessionMuxedListener l, int proto, int port) {
        throw new UnsupportedOperationException("Use MuxedImpl");
    }
    /**
     * Remove a registered listener.
     * Not supported by this implementation - nothing is ever registered here.
     *
     * @param proto ignored - nothing is ever registered here
     * @param port ignored - nothing is ever registered here
     * @throws UnsupportedOperationException always, as no listener is ever registered
     */
    @Override
    public void removeListener(int proto, int port) {
        throw new UnsupportedOperationException("Use MuxedImpl");
    }
    /**
     * Send an I2CP message.
     * Not supported by this implementation - use I2PSessionMuxedImpl.
     *
     * @return false always
     * @throws UnsupportedOperationException always, as muxed sends are unsupported here
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int proto, int fromport, int toport) throws I2PSessionException {
        throw new UnsupportedOperationException("Use MuxedImpl");
    }
    /**
     * Send an I2CP message.
     * Not supported by this implementation - use I2PSessionMuxedImpl.
     *
     * @return false always
     * @throws UnsupportedOperationException always, as muxed sends are unsupported here
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set<SessionTag> tagsSent,
                               int proto, int fromport, int toport) throws I2PSessionException {
        throw new UnsupportedOperationException("Use MuxedImpl");
    }
    /**
     * Send an I2CP message.
     * Not supported by this implementation - use I2PSessionMuxedImpl.
     *
     * @return false always
     * @throws UnsupportedOperationException always, as muxed sends are unsupported here
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set<SessionTag> tagsSent, long expire,
                               int proto, int fromport, int toport) throws I2PSessionException {
        throw new UnsupportedOperationException("Use MuxedImpl");
    }
    /**
     * Send an I2CP message.
     * Not supported by this implementation - use I2PSessionMuxedImpl.
     *
     * @return false always
     * @throws UnsupportedOperationException always, as muxed sends are unsupported here
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set<SessionTag> tagsSent, long expire,
                               int proto, int fromport, int toport, int flags) throws I2PSessionException {
        throw new UnsupportedOperationException("Use MuxedImpl");
    }
    /**
     * Send an I2CP message.
     * Not supported by this implementation - use I2PSessionMuxedImpl.
     *
     * @return false always
     * @throws UnsupportedOperationException always, as muxed sends are unsupported here
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size,
                               int proto, int fromport, int toport, SendMessageOptions options) throws I2PSessionException {
        throw new UnsupportedOperationException("Use MuxedImpl");
    }
    /**
     * Send an I2CP message.
     * Not supported by this implementation - use I2PSessionMuxedImpl.
     *
     * @return -1 always
     * @throws UnsupportedOperationException always, as muxed sends are unsupported here
     */
    @Override
    public long sendMessage(Destination dest, byte[] payload, int offset, int size,
                               int proto, int fromport, int toport,
                               SendMessageOptions options, SendMessageStatusListener listener) throws I2PSessionException {
        throw new UnsupportedOperationException("Use MuxedImpl");
    }

    /** Unused, see MuxedImpl override. */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload) throws I2PSessionException {
        return sendMessage(dest, payload, 0, payload.length);
    }

    /**
     * Send an I2CP message. No end-to-end crypto is done, so any key or tags
     * are ignored; this delegates to the no-effort/best-effort send below.
     *
     * @return whether the router accepted the message for delivery
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size) throws I2PSessionException {
        // we don't do end-to-end crypto any more
        return sendMessage(dest, payload, offset, size, null, null, 0);
    }

    /**
     * Send a message with session key and tags.
     *
     * @param keyUsed unused - no end-to-end crypto
     * @param tagsSent unused - no end-to-end crypto
     * @return whether the router accepted the message for delivery
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, SessionKey keyUsed, Set<SessionTag> tagsSent) throws I2PSessionException {
        return sendMessage(dest, payload, 0, payload.length, keyUsed, tagsSent, 0);
    }

    /**
     * Send a message with session key and tags.
     *
     * @param keyUsed unused - no end-to-end crypto
     * @param tagsSent unused - no end-to-end crypto
     * @return whether the router accepted the message for delivery
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set<SessionTag> tagsSent)
                   throws I2PSessionException {
        return sendMessage(dest, payload, offset, size, keyUsed, tagsSent, 0);
    }

    /**
     * Unused? See the MuxedImpl override.
     *
     * @param keyUsed unused - no end-to-end crypto
     * @param tagsSent unused - no end-to-end crypto
     * @return whether the router accepted the message for delivery
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set<SessionTag> tagsSent, long expires)
                   throws I2PSessionException {
        if (_log.shouldDebug()) {_log.debug("Sending message");}
        verifyOpen();
        updateActivity();

        // Sadly there is no way to send something completely uncompressed in a
        // backward-compatible way, so we still send it in gzip format, which
        // adds 23 bytes (2.4% for a 960-byte msg): 10 byte header + 5 byte
        // block header + 8 byte trailer.
        // In the future we can add a one-byte magic number != 0x1F to signal an
        // uncompressed msg (Gzip streams start with 0x1F 0x8B 0x08), assuming
        // we don't need the CRC-32 that comes with gzip.
        // Maybe implement this soon in receiveMessage() below so we are ready
        // in case we ever make an incompatible network change. This would save
        // 22 of the 23 bytes and a little CPU.
        boolean sc = shouldCompress(size);
        if (sc) {payload = DataHelper.compress(payload, offset, size);}
        else {payload = DataHelper.compress(payload, offset, size, DataHelper.NO_COMPRESSION);}
        //else throw new IllegalStateException("we need to update sendGuaranteed to support partial send");

        int compressed = payload.length;
        if (_log.shouldInfo()) {
            String d = dest.calculateHash().toBase32().substring(0,8);
            _log.info("Sending message to [" + d + "] -> Compressed: " + sc
                      + " * Size in: " + size + " bytes * Size out: " + compressed + " bytes");
        }
        if (_noEffort) {return sendNoEffort(dest, payload, expires, 0);}
        else {return sendBestEffort(dest, payload, keyUsed, tagsSent, expires);}
    }

    /**
     * Receive a message from the router, decompressing it unless it was sent
     * uncompressed.
     *
     * @param msgId the message ID
     * @return the data, or null on error
     */
    @Override
    public byte[] receiveMessage(int msgId) throws I2PSessionException {
        byte[] compressed = super.receiveMessage(msgId);
        if (compressed == null) {
            if (_log.shouldWarn()) {_log.warn("Error: [MsgID " + msgId + "] already received!");}
            return null;
        }
        if (SHOULD_DECOMPRESS) { // future - check magic number to see whether to decompress
            try {return DataHelper.decompress(compressed);}
            catch (IOException ioe) {
                if (_log.shouldWarn()) {_log.warn("Error decompressing message \n* " + ioe.getMessage());}
                return null;
            }
        }
        return compressed;
    }

    /**
     * Send a message with best effort delivery.
     *
     * @param dest the remote destination to send to
     * @param payload the message bytes to deliver
     * @param keyUsed unused - no end-to-end crypto
     * @param tagsSent unused - no end-to-end crypto
     * @param expires expiration time in ms since the epoch, 0 for the router default
     * @return whether the router accepted the message for delivery
     * @throws I2PSessionException if the wait for the router's status update is interrupted
     */
    protected boolean sendBestEffort(Destination dest, byte[] payload, SessionKey keyUsed, Set<SessionTag> tagsSent, long expires) throws I2PSessionException {
        return sendBestEffort(dest, payload, expires, 0);
    }

    /**
     * Send a best-effort message with the given flags.
     *
     * @param dest the remote destination to send to
     * @param payload the message bytes to deliver
     * @param expires expiration time in ms since the epoch, 0 for the router default
     * @param flags to be passed to the router
     * @return whether the router accepted the message for delivery
     * @throws I2PSessionException if the wait for the router's status update is interrupted
     * @since 0.8.4
     */
    protected boolean sendBestEffort(Destination dest, byte[] payload, long expires, int flags) throws I2PSessionException {
        // TODO - Don't need to save MessageState since actuallyWait is false...
        // But for now just use sendNoEffort() instead.

        long nonce = _sendMessageNonce.incrementAndGet();
        MessageState state = new MessageState(_context, nonce, getPrefix());

        /**
         * This is 'best effort', so all we wait for is a status update saying
         * that the router received it - in theory, that should come back immediately,
         * but in practice can take up to a second (though usually much quicker).
         * Setting this to false will short-circuit that delay.
         */
        boolean actuallyWait = false; // true;
        if (actuallyWait) {_sendingStates.put(Long.valueOf(nonce), state);}
        _producer.sendMessage(this, dest, nonce, payload, expires, flags);

        if (actuallyWait) {
            try {state.waitForAccept(_context.clock().now() + getTimeout());}
            catch (InterruptedException ie) {throw new I2PSessionException("interrupted");}
            finally {_sendingStates.remove(Long.valueOf(nonce));}
        }
        boolean found = !actuallyWait || state.wasAccepted();

        if (found) {
            if (_log.shouldInfo()) {
                _log.info(getPrefix() + "-> Message sent * Elapsed: " + state.getElapsed() + "ms"
                          + " * Size: " + payload.length + " bytes");
            }
        } else {
            if (_log.shouldInfo()) {
                _log.info(getPrefix() + "-> Message send failed * Elapsed: " + state.getElapsed() + "ms"
                          + " * Size: " + payload.length + " bytes");
            }
            return false;
        }
        return found;
    }

    /**
     * Same as {@link #sendBestEffort}, except we do not expect any MessageStatusMessage responses -
     * not for accepted, or success, or failure.
     * So we don't create a MessageState and save it on the _sendingStates HashSet.
     *
     * @param dest the remote destination to send to
     * @param payload the message bytes
     * @param expires expiration time in ms since the epoch, 0 for the router default
     * @param flags to be passed to the router
     * @return true always
     * @throws I2PSessionException if the router rejects the send
     * @since 0.8.1
     */
    protected boolean sendNoEffort(Destination dest, byte[] payload, long expires, int flags) throws I2PSessionException {
        _producer.sendMessage(this, dest, 0, payload, expires, flags); // nonce always 0
        return true;
    }

    /**
     * Only call this with nonzero status, i.e. for outbound messages
     * whose MessageState may be queued on _sendingStates.
     * <p>
     * Even when using sendBestEffort(), this is a waste, because the
     * MessageState is removed from _sendingStates immediately and
     * so the lookup here fails.
     * <p>
     * This is now pretty much avoided since streaming now sets
     * {@code i2cp.messageReliability} = none, which forces sendNoEffort() instead of sendBestEffort(),
     * so the router won't send us any MSM's for outbound traffic.
     *
     * @param msgId the message ID
     * @param nonce the nonce identifying the send
     * @param status != 0
     */
    @Override
    public void receiveStatus(int msgId, long nonce, int status) {
        if (_log.shouldDebug()) {
            _log.debug(getPrefix() + "-> Received a send status * MsgID: " + msgId + " * Nonce: " + nonce +
                       " * Status: " + status);
        }

        MessageState state = null;
        if ((state = _sendingStates.get(Long.valueOf(nonce))) != null) {
            if (_log.shouldDebug()) {_log.debug(getPrefix() + "-> Found a matching state");}
        } else if (!_sendingStates.isEmpty()) {
            // O(n**2)
            // shouldn't happen, router sends good nonce for all statuses as of 0.9.14
            for (MessageState s : _sendingStates.values()) {
                if (s.getMessageId() != null && s.getMessageId().getMessageId() == msgId) {
                    if (_log.shouldDebug()) {_log.debug(getPrefix() + "-> Found the send state by MsgID");}
                    state = s;
                    break;
                }
            }
        }

        if (state != null) {
            if (state.getMessageId() == null) {
                MessageId id = new MessageId();
                id.setMessageId(msgId);
                state.setMessageId(id);
            }
            state.receive(status);
            if (state.wasSuccessful()) {_sendingStates.remove(Long.valueOf(nonce));}

        } else {
            if (_log.shouldInfo()) {
                _log.info(getPrefix() + "-> No matching send state * MsgID: " + msgId + " * Nonce: " + nonce
                          + " * Status: " + status);
            }
        }
    }

    /**
     * Reconnect the I2CP session. Called by the superclass whenever it wants
     * to reconnect; overridden to also clear out the message state.
     *
     * @return whether the session was reconnected
     */
    @Override
    protected boolean reconnect() {
        // even if we succeed in reconnecting, we want to clear the old states,
        // since this will be a new sessionId
        clearStates();
        return super.reconnect();
    }

    /**
     * Clear all pending message states.
     */
    private void clearStates() {
        if (_sendingStates == null) {return;} // only null if overridden by I2PSimpleSession
        for (MessageState state : _sendingStates.values()) {state.cancel();}
        int states = _sendingStates.size();
        if (states > 0 && _log.shouldInfo()) {
            _log.info(getPrefix() + "-> Disconnecting with " + states + ' ' +
                      (states > 1 ? "states" : "state") + " unconfirmed");
        }
        _sendingStates.clear();
    }

    /**
     * Cached reflection access to {@link net.i2p.router.RouterContext#tunnelManager}.
     * Method is looked up once and reused to avoid repeated getMethod() overhead.
     * @return the tunnel manager
     */
    private Object getTunnelManager() {
        Method m = _tunnelManagerMethod;
        if (m == null) {
            try {
                m = _context.getClass().getMethod("tunnelManager");
                _tunnelManagerMethod = m;
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
        try {
            return m.invoke(_context);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Register a tunnel status listener. In a router context the listener is
     * also registered with the TunnelPoolManager, if there is one.
     *
     * @param lsnr the listener
     */
    @Override
    public void addTunnelStatusListener(TunnelStatusListener lsnr) {
        _tunnelStatusListeners.add(lsnr);
        // If in router context, also register with TunnelPoolManager for direct notifications
        if (_context.isRouterContext()) {
            Object tm = getTunnelManager();
            if (tm != null) {
                try {
                    tm.getClass().getMethod("addTunnelStatusListener", TunnelStatusListener.class).invoke(tm, lsnr);
                } catch (Exception e) {
                    // Ignore - not router context or method doesn't exist
                }
            }
        }
    }

    /**
     * Remove a tunnel status listener.
     *
     * @param lsnr the listener
     */
    @Override
    public void removeTunnelStatusListener(TunnelStatusListener lsnr) {
        _tunnelStatusListeners.remove(lsnr);
        // Also remove from TunnelPoolManager if in router context
        if (_context.isRouterContext()) {
            Object tm = getTunnelManager();
            if (tm != null) {
                try {
                    tm.getClass().getMethod("removeTunnelStatusListener", TunnelStatusListener.class).invoke(tm, lsnr);
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Switch to the new tunnel set.
     * Not supported by this implementation.
     *
     * @return false always
     */
    @Override
    public boolean switchToNewTunnel() {
        return false;
    }

    /**
     * Current tunnel pair in use.
     */
    @Override
    public void setCurrentTunnelPair(TunnelPair pair) {
        _currentTunnelPair = pair;
    }

    /** The tunnels carrying this session now. */
    private volatile TunnelPair _currentTunnelPair;

    /**
     * Return the current tunnel pair.
     *
     * @return the current tunnel pair, or null if none
     */
    @Override
    public TunnelPair getCurrentTunnelPair() {
        // First check if we have an explicitly set pair
        TunnelPair explicit = _currentTunnelPair;
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        // Fallback to reflection-based lookup (less accurate)
        if (_context.isRouterContext()) {
            Object tm = getTunnelManager();
            if (tm != null) {
                try {
                    net.i2p.data.Hash destHash = getMyDestination().calculateHash();
                    Object result = tm.getClass().getMethod("getTunnelPair", net.i2p.data.Hash.class).invoke(tm, destHash);
                    if (result != null) {
                        net.i2p.data.TunnelId[] pair = (net.i2p.data.TunnelId[]) result;
                        if (pair != null && pair.length >= 2) {
                            return new TunnelPair(pair[0], pair[1]);
                        }
                    }
                } catch (Exception e) {
                    // Ignore - not router context or method doesn't exist
                }
            }
        }
        return null;
    }

    /**
     * Rebuild the tunnel set.
     */
    @Override
    public void rebuildTunnels() {
        // Trigger the tunnel pool to build new tunnels
        if (_context.isRouterContext()) {
            Object tm = getTunnelManager();
            if (tm != null) {
                try {
                    tm.getClass().getMethod("tunnelFailed").invoke(tm);
                } catch (Exception e) {
                    // Ignore - not router context or method doesn't exist
                }
            }
        }
    }

    /**
     * Notify all tunnel status listeners of a tunnel failure.
     *
     * @param poolName the pool the failed tunnel belonged to
     * @param isInbound true if the tunnel was an inbound one
     * @since 0.9.69
     */
    protected void notifyTunnelFailed(String poolName, boolean isInbound) {
        for (TunnelStatusListener lsnr : _tunnelStatusListeners) {
            try {
                lsnr.tunnelFailed(poolName, isInbound);
            } catch (Throwable t) {
                if (_log.shouldWarn())
                    _log.warn("Error notifying tunnel listener", t);
            }
        }
    }

    /**
     * Notify all tunnel status listeners of a tunnel removal.
     *
     * @param event identifies the removed tunnel, its pool, direction and the reason
     * @since 0.9.69+
     */
    protected void notifyTunnelRemoved(TunnelRemovalEvent event) {
        for (TunnelStatusListener lsnr : _tunnelStatusListeners) {
            try {
                lsnr.tunnelRemoved(event);
            } catch (Throwable t) {
                if (_log.shouldWarn())
                    _log.warn("Error notifying tunnel removal listener", t);
            }
        }
    }

    /**
     * Notify all tunnel status listeners of pool shutdown.
     *
     * @param poolName the pool being shut down
     * @param isInbound true if the pool builds inbound tunnels
     * @since 0.9.69+
     */
    protected void notifyPoolShuttingDown(String poolName, boolean isInbound) {
        for (TunnelStatusListener lsnr : _tunnelStatusListeners) {
            try {
                lsnr.poolShuttingDown(poolName, isInbound);
            } catch (Throwable t) {
                if (_log.shouldWarn())
                    _log.warn("Error notifying pool shutdown listener", t);
            }
        }
    }

}
