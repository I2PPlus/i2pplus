package net.i2p.data.i2cp;

/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 *
 */

import java.io.IOException;
import java.io.InputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.ByteArrayStream;

/**
 * Response to DestLookupMessage.
 * As of 0.8.3, the response may include the hash from the request, indicating
 * a failure for a specific request.
 * Payload may be empty (failure), a Hash (failure), or a Destination.
 */
public class DestReplyMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 35;
    private Destination _dest;
    private Hash _hash;

    public DestReplyMessage() {
        super();
    }

    public DestReplyMessage(Destination d) {
        _dest = d;
    }

    /**
     *  @param h non-null with non-null data
     *  @since 0.8.3
     */
    public DestReplyMessage(Hash h) {
        _hash = h;
    }

    public Destination getDestination() {
        return _dest;
    }

    /**
     *  @since 0.8.3
     */
    public Hash getHash() {
        return _hash;
    }

    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        if (size == 0) {
            _dest = null;
            _hash = null;
        } else {
            try {
                if (size == Hash.HASH_LENGTH) {
                    _hash = Hash.create(in);
                } else {
                    _dest = Destination.create(in);
                }
            } catch (DataFormatException dfe) {
                _dest = null;
                _hash = null;
            }
        }
    }

    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_dest == null) {
            if (_hash == null)
                return new byte[0];  // null response allowed
            return _hash.getData();
        }
        ByteArrayStream os = new ByteArrayStream(_dest.size());
        try {
            _dest.writeBytes(os);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the dest", dfe);
        }
        return os.toByteArray();
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[DestReplyMessage: ");
        buf.append("\n\tDestination: ").append(_dest);
        buf.append("\n\tHash: ").append(_hash);
        buf.append("]");
        return buf.toString();
    }
}
