package net.i2p.data.i2np;

import net.i2p.I2PAppContext;

/**
 *  The basic build message with 8 records.
 */
public class TunnelBuildMessage extends TunnelBuildMessageBase {

    /**
     * The I2NP message type number for a tunnel build message.
     */
    public static final int MESSAGE_TYPE = 21;

    /**
     * Creates a build message with the default record count.
     *
     * @param context the application context the message is read and written under
     */
    public TunnelBuildMessage(I2PAppContext context) {
        super(context, MAX_RECORD_COUNT);
    }

    /**
     * Creates a build message carrying the given number of build records.
     * @param context the application context the message is read and written under
     * @param records the number of build records this message carries
     * @since 0.7.12
     */
    protected TunnelBuildMessage(I2PAppContext context, int records) {
        super(context, records);
    }

    /**
     * The I2NP message type of a build message.
     * @return the type
     */
    public int getType() {return MESSAGE_TYPE;}

    /**
     * String form for debugging.
     */
    @Override
    public String toString() {return "[TunnelBuildMessage]";}
}
