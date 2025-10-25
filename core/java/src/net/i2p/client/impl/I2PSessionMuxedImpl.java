package net.i2p.client.impl;

import java.io.InputStream;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionListener;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.client.SendMessageOptions;
import net.i2p.client.SendMessageStatusListener;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.i2cp.MessagePayloadMessage;
import net.i2p.util.Log;

/**
 * I2PSessionMuxedImpl
 *
 * This class extends I2PSessionImpl2 to provide a muxed (multiplexed) session
 * capable of handling multiple protocols/ports over a single I2CP stream.
 *
 * Key responsibilities:
 * - Manage a demultiplexer to dispatch incoming messages to registered listeners
 * - Provide a muxed availability notifier to asynchronously inform listeners about
 *   message availability (with proto/port metadata)
 * - Offer multiple variants of sendMessage, including options for gzip, proto, ports, and status callbacks
 *
 * Design notes:
 * - The demultiplexer and availability notifier are lightweight, serializing notifications
 *   onto a dedicated thread/queue to minimize contention on the I2P context's main threads.
 * - Header embedding for protocol/ports is preserved for backward compatibility with
 *   existing muxed payloads and gzip header handling.
 *
 * @author zzz
 * @since 0.7.1 with refactor by dr|z3d for 0.9.68+
 */
class I2PSessionMuxedImpl extends I2PSessionImpl2 {

    /** Demultiplexer used to route traffic to registered listeners */
    private final I2PSessionDemultiplexer _demultiplexer;

    /**
     * Primary constructor.
     * Initializes demultiplexer and assigns it as the session listener.
     * Also prepares a dedicated availability notifier for muxed scenarios.
     *
     * @param ctx the application context
     * @param destKeyStream stream containing the private key data
     * @param options optional configuration properties; if null, system properties are used
     * @throws I2PSessionException if session initialization fails
     */
    public I2PSessionMuxedImpl(I2PAppContext ctx, InputStream destKeyStream, Properties options) throws I2PSessionException {
        super(ctx, destKeyStream, options);

        // Create a dedicated demultiplexer and install as the session listener.
        // This ensures all incoming messages are dispatched through a single routing layer.
        _demultiplexer = new I2PSessionDemultiplexer(ctx);
        super.setSessionListener(_demultiplexer);

        // Use a specialized notifier for muxed scenarios to deliver availability events.
        _availabilityNotifier = new MuxedAvailabilityNotifier();
    }

    /*
     * For extension by SubSession
     *
     * @param destKeyStream stream containing the private key data
     * @param options set of options to configure the router with
     * @since 0.9.21
     */
    protected I2PSessionMuxedImpl(I2PSessionImpl primary, InputStream destKeyStream, Properties options) throws I2PSessionException {
        super(primary, destKeyStream, options);

        _demultiplexer = new I2PSessionDemultiplexer(primary.getContext());
        super.setSessionListener(_demultiplexer);

        _availabilityNotifier = new MuxedAvailabilityNotifier();
    }

    /** listen on all protocols and ports */
    @Override
    public void setSessionListener(I2PSessionListener lsnr) {
        // Register the listener to receive all protocol/port combinations.
        _demultiplexer.addListener(lsnr, PROTO_ANY, PORT_ANY);
    }

    /**
     * Listen on specified protocol and port.
     * An existing listener with the same proto and port is replaced.
     * Only the listener with the best match is called back for each message.
     *
     * @param proto 1-254 or PROTO_ANY (0) for all; recommended:
     *         I2PSession.PROTO_STREAMING
     *         I2PSession.PROTO_DATAGRAM
     *         255 disallowed
     * @param port 1-65535 or PORT_ANY (0) for all
     */
    @Override
    public void addSessionListener(I2PSessionListener lsnr, int proto, int port) {
        _demultiplexer.addListener(lsnr, proto, port);
    }

    /**
     * Listen on specified protocol and port, and receive notification
     * of proto, fromPort, and toPort for every message.
     *
     * @param proto 1-254 or PROTO_ANY (0) for all; 255 disallowed
     * @param port 1-65535 or PORT_ANY (0) for all
     */
    @Override
    public void addMuxedSessionListener(I2PSessionMuxedListener l, int proto, int port) {
        _demultiplexer.addMuxedListener(l, proto, port);
    }

    /** removes the specified listener (only) */
    @Override
    public void removeListener(int proto, int port) {
        _demultiplexer.removeListener(proto, port);
    }

    @Override
    public boolean sendMessage(Destination dest, byte[] payload) throws I2PSessionException {
        return sendMessage(dest, payload, 0, payload.length, null, null,
                           0, PROTO_UNSPECIFIED, PORT_UNSPECIFIED, PORT_UNSPECIFIED);
    }

    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int proto, int fromport, int toport) throws I2PSessionException {
        return sendMessage(dest, payload, 0, payload.length, null, null, 0, proto, fromport, toport);
    }

    /**
     * @param keyUsed unused - no end-to-end crypto
     * @param tagsSent unused - no end-to-end crypto
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size,
                               SessionKey keyUsed, Set<SessionTag> tagsSent, long expires)
                   throws I2PSessionException {
        return sendMessage(dest, payload, offset, size, keyUsed, tagsSent, 0, PROTO_UNSPECIFIED, PORT_UNSPECIFIED, PORT_UNSPECIFIED);
    }

    /**
     * @param keyUsed unused - no end-to-end crypto
     * @param tagsSent unused - no end-to-end crypto
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set<SessionTag> tagsSent,
                               int proto, int fromport, int toport) throws I2PSessionException {
        return sendMessage(dest, payload, offset, size, keyUsed, tagsSent, 0, proto, fromport, toport);
    }

    /**
     *  @param keyUsed unused - no end-to-end crypto
     *  @param tagsSent unused - no end-to-end crypto
     *  @param proto 1-254 or 0 for unset; recommended:
     *         I2PSession.PROTO_UNSPECIFIED
     *         I2PSession.PROTO_STREAMING
     *         I2PSession.PROTO_DATAGRAM
     *         255 disallowed
     *  @param fromPort 1-65535 or 0 for unset
     *  @param toPort 1-65535 or 0 for unset
     *  @since 0.7.1
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size,
                               SessionKey keyUsed, Set<SessionTag> tagsSent, long expires,
                               int proto, int fromPort, int toPort)
                   throws I2PSessionException {
        return sendMessage(dest, payload, offset, size, keyUsed, tagsSent, 0, proto, fromPort, toPort, 0);
    }

    /**
     *  @param keyUsed unused - no end-to-end crypto
     *  @param tagsSent unused - no end-to-end crypto
     *  @param proto 1-254 or 0 for unset; recommended:
     *         I2PSession.PROTO_UNSPECIFIED
     *         I2PSession.PROTO_STREAMING
     *         I2PSession.PROTO_DATAGRAM
     *         255 disallowed
     *  @param fromPort 1-65535 or 0 for unset
     *  @param toPort 1-65535 or 0 for unset
     *  @param flags to be passed to the router
     *  @since 0.8.4
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size,
                               SessionKey keyUsed, Set<SessionTag> tagsSent, long expires,
                               int proto, int fromPort, int toPort, int flags)
                   throws I2PSessionException {
        // Prepare payload with gzip flag as appropriate, then delegate to router
        payload = prepPayload(payload, offset, size, proto, fromPort, toPort, SendMessageOptions.GzipOption.DEFAULT);
        if (_noEffort) {
            SendMessageOptions options = new SendMessageOptions();
            return sendNoEffort(dest, payload, options);
        } else {
            return sendBestEffort(dest, payload, expires, flags);
        }
    }

    /**
     * See SendMessageOptions for option details.
     *
     * Always uses sendNoEffort for now.
     *
     * @param proto 1-254 or 0 for unset; recommended:
     *         I2PSession.PROTO_UNSPECIFIED
     *         I2PSession.PROTO_STREAMING
     *         I2PSession.PROTO_DATAGRAM
     *         255 disallowed
     * @param fromPort 1-65535 or 0 for unset
     * @param toPort 1-65535 or 0 for unset
     * @param options to be passed to the router
     * @since 0.9.2
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size,
                               int proto, int fromPort, int toPort, SendMessageOptions options) throws I2PSessionException {
        payload = prepPayload(payload, offset, size, proto, fromPort, toPort, options.getGzip());
        // Always use NoEffort in this simplified path
        sendNoEffort(dest, payload, options);
        return true;
    }

    /**
     * Send a message and request an asynchronous notification of delivery status.
     *
     * See I2PSessionMuxedImpl for proto/port details.
     * See SendMessageOptions for option details.
     *
     * @return the message ID to be used for later notification to the listener
     * @throws I2PSessionException on all errors
     * @since 0.9.14
     */
    @Override
    public long sendMessage(Destination dest, byte[] payload, int offset, int size,
                            int proto, int fromPort, int toPort,
                            SendMessageOptions options, SendMessageStatusListener listener) throws I2PSessionException {
        payload = prepPayload(payload, offset, size, proto, fromPort, toPort, options.getGzip());

        // Generate a unique nonce for this message and track its state for callbacks
        long nonce = _sendMessageNonce.incrementAndGet();
        long expires = Math.max(_context.clock().now() + 60 * 1000L, options.getTime());

        MessageState state = new MessageState(_context, nonce, this, expires, listener);
        _sendingStates.put(Long.valueOf(nonce), state);

        _producer.sendMessage(this, dest, nonce, payload, options);
        return nonce;
    }

    /**
     * @return gzip compressed payload, ready to send
     * @since 0.9.14
     */
    private byte[] prepPayload(byte[] payload, int offset, int size, int proto,
                               int fromPort, int toPort,
                               SendMessageOptions.GzipOption gzo) throws I2PSessionException {
        verifyOpen();
        updateActivity();

        boolean docompress;
        if (gzo == SendMessageOptions.GzipOption.DEFAULT)
            docompress = shouldCompress(size);
        else
            docompress = gzo == SendMessageOptions.GzipOption.GZIP_ON;

        if (docompress)
            payload = DataHelper.compress(payload, offset, size);
        else
            payload = DataHelper.compress(payload, offset, size, DataHelper.NO_COMPRESSION);

        setProto(payload, proto);
        setFromPort(payload, fromPort);
        setToPort(payload, toPort);

        _context.statManager().addRateData("i2cp.tx.msgCompressed", payload.length);
        _context.statManager().addRateData("i2cp.tx.msgExpanded", size);
        return payload;
    }

    /**
     * @since 0.9.2
     */
    private boolean sendNoEffort(Destination dest, byte payload[], SendMessageOptions options)
                    throws I2PSessionException {
        // nonce always 0
        _producer.sendMessage(this, dest, 0, payload, options);
        return true;
    }

    /**
     * Receive a payload message and let the app know its available
     */
    @Override
    public void addNewMessage(MessagePayloadMessage msg) {
        Long mid = Long.valueOf(msg.getMessageId());
        _availableMessages.put(mid, msg);

        long id = msg.getMessageId();
        byte data[] = msg.getPayload().getUnencryptedData();

        if ((data == null) || (data.length <= 0)) {
            if (_log.shouldLog(Log.CRIT))
                _log.log(Log.CRIT, getPrefix() + "addNewMessage of a message with no unencrypted data",
                           new Exception("Empty message"));
            return;
        }

        int size = data.length;
        if (size < 10) {
            _log.error(getPrefix() + "Length too short for gzip header: " + size);
            return;
        }

        ((MuxedAvailabilityNotifier)_availabilityNotifier).available(id, size, getProto(msg),
                                                                     getFromPort(msg), getToPort(msg));
    }

    protected class MuxedAvailabilityNotifier extends AvailabilityNotifier {
        private final LinkedBlockingQueue<MsgData> _msgs;
        private volatile boolean _alive;
        private static final int POISON_SIZE = -99999;
        private final AtomicBoolean stopping = new AtomicBoolean();

        public MuxedAvailabilityNotifier() {
            _msgs = new LinkedBlockingQueue<MsgData>();
        }

        @Override
        public void stopNotifying() {
            synchronized (stopping) {
                if( !stopping.getAndSet(true)) {
                    _msgs.clear();
                    if (_alive) {
                        boolean again = true;
                        while(again) {
                            try {
                                _msgs.put(new MsgData(0, POISON_SIZE, 0, 0, 0));
                                again = false;
                            } catch (InterruptedException ie) {
                                // retry on interruption
                                continue;
                            }
                        }
                        _alive = false;
                    }
                    stopping.set(false);
                }
            }
        }

        public void available(long msgId, int size, int proto, int fromPort, int toPort) {
            try {
                _msgs.put(new MsgData((int)(msgId & 0xffffffff), size, proto, fromPort, toPort));
            } catch (InterruptedException ie) {
                // If interrupted while enqueuing, drop this notification to avoid stalling
            }
            if (!_alive && _log.shouldWarn())
                _log.warn(getPrefix() + "Message available but notifier not running...");
        }

        @Override
        public void run() {
            if (_log.shouldDebug())
                _log.debug(getPrefix() + "Starting muxed availability notifier...");
            _msgs.clear();
            _alive = true;
            while (_alive) {
                MsgData msg;
                try {
                    msg = _msgs.take();
                } catch (InterruptedException ie) {
                    if (_log.shouldDebug())
                        _log.debug("I2PSessionMuxedImpl.run() InterruptedException " +
                                    String.valueOf(_msgs.size()) + " Messages, Alive " + _alive);
                    continue;
                }

                if (msg.size == POISON_SIZE) {
                    break;
                }

                try {
                    _demultiplexer.messageAvailable(I2PSessionMuxedImpl.this,
                        msg.id, msg.size, msg.proto, msg.fromPort, msg.toPort);
                } catch (RuntimeException e) {
                    _log.error("Error notifying app of message availability", e);
                }
            }
        }
    }

    /** lightweight container for message metadata */
    private static class MsgData {
        public final int id, size, proto, fromPort, toPort;
        public MsgData(int i, int s, int p, int f, int t) {
            id = i;
            size = s;
            proto = p;
            fromPort = f;
            toPort = t;
        }
    }

    /**
     *  No, we couldn't put any protocol byte in front of everything and
     *  keep backward compatibility. But there are several bytes that
     *  are unused AND unchecked in the gzip header in releases <= 0.7.
     *  So let's use 5 of them for a protocol and two 2-byte ports.
     *
     *  The fields used are all ignored on receive in ResettableGzipInputStream
     *
     *  See also ResettableGzipOutputStream.
     *  Ref: RFC 1952
     *
     */

    /** OS byte in gzip header */
    private static final int PROTO_BYTE = 9;

    /** Upper two bytes of MTIME in gzip header */
    private static final int FROMPORT_BYTES = 4;

    /** Lower two bytes of MTIME in gzip header */
    private static final int TOPORT_BYTES = 6;

    /** Non-muxed sets the OS byte to 0xff */
    private static int getProto(MessagePayloadMessage msg) {
        int rv = getByte(msg, PROTO_BYTE) & 0xff;
        return rv == 0xff ? PROTO_UNSPECIFIED : rv;
    }

    /** Non-muxed sets the MTIME bytes to 0 */
    private static int getFromPort(MessagePayloadMessage msg) {
        return (((getByte(msg, FROMPORT_BYTES) & 0xff) << 8) |
                 (getByte(msg, FROMPORT_BYTES + 1) & 0xff));
    }

    /** Non-muxed sets the MTIME bytes to 0 */
    private static int getToPort(MessagePayloadMessage msg) {
        return (((getByte(msg, TOPORT_BYTES) & 0xff) << 8) |
                 (getByte(msg, TOPORT_BYTES + 1) & 0xff));
    }

    private static int getByte(MessagePayloadMessage msg, int i) {
        return msg.getPayload().getUnencryptedData()[i] & 0xff;
    }

    private static void setProto(byte[] payload, int p) {
        payload[PROTO_BYTE] = (byte) (p & 0xff);
    }

    private static void setFromPort(byte[] payload, int p) {
        payload[FROMPORT_BYTES] = (byte) ((p >> 8) & 0xff);
        payload[FROMPORT_BYTES + 1] = (byte) (p & 0xff);
    }

    private static void setToPort(byte[] payload, int p) {
        payload[TOPORT_BYTES] = (byte) ((p >> 8) & 0xff);
        payload[TOPORT_BYTES + 1] = (byte) (p & 0xff);
    }
}
