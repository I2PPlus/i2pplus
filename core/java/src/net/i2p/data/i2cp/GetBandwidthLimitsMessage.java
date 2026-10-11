package net.i2p.data.i2cp;

/*
 * public domain
 *
 */

import java.io.IOException;
import java.io.InputStream;

/**
 * Request the router tells us the current bw limits
 *
 * @author zzz
 */
public class GetBandwidthLimitsMessage extends I2CPMessageImpl {
    /**
     * I2CP message type number identifying a bandwidth limit query.
     */
    public static final int MESSAGE_TYPE = 8;

    /**
     * No data to initialize for this message type.
     */
    public GetBandwidthLimitsMessage() {
        super();
    }

    /**
     * Read the message from the stream. No data is read for this message type.
     */
    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        // noop
    }

    /**
     * Write the message body to the output stream.
     */
    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        return new byte[0];
    }

    /**
     * Reports this message's I2CP type number.
     * @return the I2CP message type ID, always {@link #MESSAGE_TYPE}
     */
    @Override
    public int getType() {
        return MESSAGE_TYPE;
    }
/** Returns a string representation of this message. */
    @Override
    public String toString() {
        return "[GetBandwidthLimitsMessage]";
    }
}
