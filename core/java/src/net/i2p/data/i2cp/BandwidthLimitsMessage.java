package net.i2p.data.i2cp;

/*
 * public domain
 *
 */

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.ByteArrayStream;

import java.io.IOException;
import java.io.InputStream;

/**
 * Tell the other side the limits
 *
 * @author zzz
 */
public class BandwidthLimitsMessage extends I2CPMessageImpl {
    /** Message type identifier for bandwidth limit messages */
    public static final int MESSAGE_TYPE = 23;
    private static final int LIMITS = 16;
    private int[] data;

    /** Creates a new BandwidthLimitsMessage with default values. */
    public BandwidthLimitsMessage() {
        super();
        data = new int[LIMITS];
    }

    /**
     *  Slot definitions. Slots 7-15 are left undefined and wasted — this
     *  message is only local and rarely sent, so we don't care about the waste.
     *
     * 0) Client inbound limit (KBps)
     * 1) Client outbound limit (KBps)
     * 2) Router inbound limit (KBps)
     * 3) Router inbound burst limit (KBps)
     * 4) Router outbound limit (KBps)
     * 5) Router outbound burst limit (KBps)
     * 6) Router burst time (seconds)
     * 7-15) undefined
     */
    /**
     * Construct with client bandwidth limits.
     *
     * @param in Client inbound limit (KBps)
     * @param out Client outbound limit (KBps)
     */
    public BandwidthLimitsMessage(int in, int out) {
        this();
        data[0] = in;
        data[1] = out;
    }

    /**
     * Construct with all bandwidth limits.
     *
     * @param in Client inbound limit (KBps)
     * @param out Client outbound limit (KBps)
     * @param rin Router inbound limit (KBps)
     * @param rinb Router inbound burst limit (KBps)
     * @param rout Router outbound limit (KBps)
     * @param routb Router outbound burst limit (KBps)
     * @param sec Router burst time (seconds)
     *
     * @since 0.9.62
     */
    public BandwidthLimitsMessage(int in, int out, int rin, int rinb, int rout, int routb, int sec) {
        this();
        data[0] = in;
        data[1] = out;
        data[2] = rin;
        data[3] = rinb;
        data[4] = rout;
        data[5] = routb;
        data[6] = sec;
    }

    /**
     * Get the current bandwidth limits.
     *
     * @return the current bandwidth limits array
     */
    public int[] getLimits() {
        return data;
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            for (int i = 0; i < LIMITS; i++) {
                data[i] = (int) DataHelper.readLong(in, 4);
            }
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        ByteArrayStream os = new ByteArrayStream(4 * LIMITS);
        try {
            for (int i = 0; i < LIMITS; i++) {
                DataHelper.writeLong(os, 4, data[i]);
            }
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }

    @Override
    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(); // NOPMD - AvoidUnnecessaryStringBuilderCreation
        buf.append("[BandwidthLimitsMessage");
        buf.append("\n\tIn: ").append(data[0]);
        buf.append("\n\tOut: ").append(data[1]);
        buf.append("]");
        return buf.toString();
    }
}
