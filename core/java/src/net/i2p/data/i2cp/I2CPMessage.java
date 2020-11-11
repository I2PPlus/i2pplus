package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Defines the base functionality of API messages
 *
 * As of 0.9.48, does NOT extend DataStructure.
 *
 * @author jrandom
 */
public interface I2CPMessage {
    /**
     * Read the contents from the input stream into the current class's format.
     * The stream should be the message body as defined by the client access layer
     * specification after the message header (4 bytes specifying the size of the 
     * message, 1 byte specifying the type of the message).  
     * 
     * @param in stream to read from
     * @param size number of bytes in the message payload
     * @param type type of message (should equal getType())
     * @throws I2CPMessageException if the stream doesn't contain a valid message
     *          that this class can read.
     * @throws IOException if there is a problem reading from the stream
     */
    public void readMessage(InputStream in, int size, int type) throws I2CPMessageException, IOException;

    /**
     * Read the contents from the input stream into the current class's format.
     * The stream should be the message header and body as defined by the I2CP 
     * specification 
     * 
     * @param in stream to read from
     * @throws I2CPMessageException if the stream doesn't contain a valid message
     *          that this class can read.
     * @throws IOException if there is a problem reading from the stream
     */
    public void readMessage(InputStream in) throws I2CPMessageException, IOException;

    /**
     * Write the current message to the output stream as a full message following
     * the specification from the I2CP definition.
     * 
     * @param out OutputStream
     * @throws I2CPMessageException if the current object doesn't have sufficient data
     *          to write a properly formatted message.
     * @throws IOException if there is a problem writing to the stream
     */
    public void writeMessage(OutputStream out) throws I2CPMessageException, IOException;

    /**
     * Return the unique identifier for this type of message, as specified in the 
     * network specification document under #ClientAccessLayerMessages
     * @return unique identifier for this type of message
     */
    public int getType();

    /**
     * Return the SessionId for this type of message.
     * Most but not all message types include a SessionId.
     * The ones that do already define getSessionId(), but some return a SessionId and
     * some return a long, so we define a new method here.
     *
     * @return SessionId or null if this message type does not include a SessionId
     * @since 0.9.21
     */
    public SessionId sessionId();
}
