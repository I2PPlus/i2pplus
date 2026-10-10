package net.i2p.sam;

import java.io.Closeable;
import net.i2p.client.I2PSessionException;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;

/**
 * Base for datagram/raw sessions (v1/v3) and also SAMStreamSession.
 * @see SAMMessageSession
 * @see SAMStreamSession
 */
interface SAMMessageSess extends Closeable {

    /**
     * Start a SAM message-based session.
     * MUST be called after constructor.
     */
    public void start();

    /**
     * Close a SAM message-based session.
     */
    public void close();

    /**
     * Get the SAM message-based session Destination.
     *
     * @return The SAM message-based session Destination.
     */
    public Destination getDestination();

    /**
     * Send bytes through a SAM message-based session.
     *
     * @param dest the base32 destination or .b32.i2p hostname to send to
     * @param data the bytes to send
     * @param proto the I2CP protocol to send under
     * @param fromPort the I2CP from port, 0 for the default
     * @param toPort the I2CP to port, 0 for the default
     *
     * @return True if the data was sent, false otherwise
     * @throws DataFormatException on unknown / bad dest
     * @throws I2PSessionException on serious error, probably session closed
     */
    public boolean sendBytes(String dest, byte[] data, int proto,
                             int fromPort, int toPort) throws DataFormatException, I2PSessionException;

    /**
     * Send bytes through a SAM message-based session, with per-message
     * extended options.
     *
     * @param dest the base32 destination or .b32.i2p hostname to send to
     * @param data the bytes to send
     * @param proto the I2CP protocol to send under
     * @param fromPort the I2CP from port, 0 for the default
     * @param toPort the I2CP to port, 0 for the default
     * @param sendLeaseSet true to include the LeaseSet in the message
     * @param sendTags the number of tunnels to include, 0 to leave as default
     * @param tagThreshold the build success ratio below which no tunnel is sent, 0 for default
     * @param expiration the lease lifetime in seconds from now, 0 to leave as default
     * @return True if the data was sent, false otherwise
     * @throws DataFormatException if dest is not a valid destination
     * @throws I2PSessionException on serious error, probably session closed
     * @since 0.9.25
     */
    public boolean sendBytes(String dest, byte[] data, int proto,
                             int fromPort, int toPort,
                             boolean sendLeaseSet, int sendTags,
                             int tagThreshold, int expiration)
                                   throws DataFormatException, I2PSessionException;

    /**
     * Get the I2CP protocol for this session's listener.
     *
     * @return the I2CP protocol
     */
    public int getListenProtocol();

    /**
     * Get the port for this session's listener.
     *
     * @return the listen port
     */
    public int getListenPort();

    /**
     * Lookup a destination through the I2CP session.
     * Blocking.
     *
     * @param name the base32 hostname, or full destination, to resolve
     * @return the Destination, or null if it could not be resolved
     * @throws I2PSessionException if the lookup does not complete within the
     *         10 second session timeout
     * @since 0.9.69
     */
    public Destination lookupDest(String name) throws I2PSessionException;
}
