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
     * MESSAGE_TYPE.
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
     * Type.
     * @return the type
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
