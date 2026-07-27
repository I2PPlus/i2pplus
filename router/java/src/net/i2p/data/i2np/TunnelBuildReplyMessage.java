package net.i2p.data.i2np;

import net.i2p.I2PAppContext;

/**
 *  The basic build reply message with 8 records.
 * Transmitted from the new outbound endpoint to the creator through a
 * reply tunnel
 */
public class TunnelBuildReplyMessage extends TunnelBuildMessageBase {
    /** default constructor */

    public static final int MESSAGE_TYPE = 22;

    /**
     * TunnelBuildReplyMessage.
     */
    public TunnelBuildReplyMessage(I2PAppContext context) {
        super(context, MAX_RECORD_COUNT);
    }

    /**
     * Create a tunnel build reply message with a custom record count.
     *
     * @param context the I2P app context
     * @param records the number of records
     * @since 0.7.12
     */
    protected TunnelBuildReplyMessage(I2PAppContext context, int records) {
        super(context, records);
    }

    /**
     * getType.
     */
    public int getType() { return MESSAGE_TYPE; }

    /**
     * toString.
     */
    @Override
    public String toString() {
        return "[TunnelBuildReplyMessage]";
    }
}
