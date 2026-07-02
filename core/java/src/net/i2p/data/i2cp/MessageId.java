package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Defines the message ID of a message delivered between a router and a client
 * in a particular session.  These IDs are not globally unique.
 *
 * As of 0.9.48, does NOT extend DataStructureImpl.
 *
 * @author jrandom
 */
public class MessageId {
    private long _messageId;

    /** */
    public MessageId() {
        _messageId = -1;
    }

    /**
     *  @param id the message ID
     */
    public MessageId(long id) {
        _messageId = id;
    }

    /**
     *  @return the message ID
     */
    public long getMessageId() {
        return _messageId;
    }

    /**
     *  Set the message ID.
     *
     *  @param id the message ID
     */
    public void setMessageId(long id) {
        _messageId = id;
    }

    /**
     *  Read the message ID from a stream.
     *
     *  @param in the input stream to read from
     *  @throws DataFormatException if the data is invalid
     *  @throws IOException if there is an error reading
     */
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _messageId = DataHelper.readLong(in, 4);
    }

    /**
     *  Write the message ID to a stream.
     *
     *  @param out the output stream to write to
     *  @throws DataFormatException if the message ID is invalid
     *  @throws IOException if there is an error writing
     */
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_messageId < 0) throw new DataFormatException("Invalid message ID: " + _messageId);
        DataHelper.writeLong(out, 4, _messageId);
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object object) {
        if ((object == null) || !(object instanceof MessageId)) {
            return false;
        }
        return _messageId == ((MessageId) object).getMessageId();
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return (int) _messageId;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "[MsgID " + _messageId + "]";
    }
}
