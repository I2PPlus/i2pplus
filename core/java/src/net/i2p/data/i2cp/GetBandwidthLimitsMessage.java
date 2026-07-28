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
     * Constructs a new GetBandwidthLimitsMessage.
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
     * doWriteMessage.
     */
    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        byte[] rv = new byte[0];
        return rv;
    }

    /**
     * @return the type
     */
    @Override
    public int getType() {
        return MESSAGE_TYPE;
    }

    /**
     * toString.
     */
    @Override
    public String toString() {
        return "[GetBandwidthLimitsMessage]";
    }
}
