package net.i2p.data.i2np;

import net.i2p.I2PAppContext;

/**
 *  The basic build message with 8 records.
 */
public class TunnelBuildMessage extends TunnelBuildMessageBase {

    /**
     * MESSAGE_TYPE.
     */
    public static final int MESSAGE_TYPE = 21;

    /**
     * TunnelBuildMessage.
     */
    public TunnelBuildMessage(I2PAppContext context) {
        super(context, MAX_RECORD_COUNT);
    }

    /** @since 0.7.12 */
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
