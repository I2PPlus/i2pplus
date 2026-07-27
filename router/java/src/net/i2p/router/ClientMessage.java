package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Payload;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.SessionConfig;

/**
 * Wrap a message either destined for a local client or received from one.
 * Note that an outbound message may get routed as an inbound message
 * for local-local communication.
 *
 * @author jrandom
 */
public class ClientMessage {
    private final Payload _payload;
    private final Destination _destination;
    private final Destination _fromDestination;
    private final SessionConfig _senderConfig;
    private final Hash _destinationHash;
    private final MessageId _messageId;
    private final long _messageNonce;
    private final long _expiration;
    /** only for outbound messages */
    private final int _flags;

    /**
     *  For outbound (locally originated)
     *
     *  @param toDest the destination to send to
     *  @param payload the message payload
     *  @param config the session config of the sending client
     *  @param fromDest the source destination
     *  @param msgID the router's ID for this message
     *  @param messageNonce the client's ID for this message
     *  @param expiration the expiration time
     *  @param flags the message flags
     *  @since 0.9.9
     */
    public ClientMessage(Destination toDest, Payload payload, SessionConfig config, Destination fromDest,
                         MessageId msgID, long messageNonce, long expiration, int flags) {
        _destination = toDest;
        _destinationHash = null;
        _payload = payload;
        _senderConfig = config;
        _fromDestination = fromDest;
        _messageId = msgID;
        _messageNonce = messageNonce;
        _expiration = expiration;
        _flags = flags;
    }

    /**
     *  For inbound (from remote dest)
     *
     *  @param toDestHash the destination hash
     *  @param payload the message payload
     *  @since 0.9.9
     */
    public ClientMessage(Hash toDestHash, Payload payload) {
        _destination = null;
        _destinationHash = toDestHash;
        _payload = payload;
        _senderConfig = null;
        _fromDestination = null;
        _messageId = null;
        _messageNonce = 0;
        _expiration = 0;
        _flags = 0;
    }

    /**
     * Retrieve the payload of the message.  All ClientMessage objects should have
     * a payload
     *
     * @return the payload
     */
    public Payload getPayload() { return _payload; }

    /**
     * Retrieve the destination to which this message is directed.
     * Valid for outbound; null for inbound.
     * If null, use getDestinationHash()
     *
     * @return the destination
     */
    public Destination getDestination() { return _destination; }

    /**
     * Valid for outbound; null for inbound.
     *
     * @return the from destination
     */
    public Destination getFromDestination() { return _fromDestination; }

    /**
     * Retrieve the destination to which this message is directed.
     * Valid for inbound; null for outbound.
     * If null, use getDestination()
     *
     * @return the destination hash
     */
    public Hash getDestinationHash() { return _destinationHash; }

    /**
     * Valid for outbound; null for inbound.
     *
     * @return the message ID
     */
    public MessageId getMessageId() { return _messageId; }

    /**
     * Valid for outbound; 0 for inbound.
     *
     * @return the message nonce
     * @since 0.9.14
     */
    public long getMessageNonce() { return _messageNonce; }

    /**
     * Retrieve the session config of the client that sent the message.  This will only be available
     * if the client was local
     *
     * @return the session config
     */
    public SessionConfig getSenderConfig() { return _senderConfig; }

    /**
     * Expiration requested by the client that sent the message.  This will only be available
     * for locally originated messages.
     *
     * @return the expiration
     */
    public long getExpiration() { return _expiration; }

    /**
     * Flags requested by the client that sent the message.  This will only be available
     * for locally originated messages.
     *
     * @return the flags
     * @since 0.8.4
     */
    public int getFlags() { return _flags; }
}
