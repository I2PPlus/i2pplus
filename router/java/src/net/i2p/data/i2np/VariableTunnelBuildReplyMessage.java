package net.i2p.data.i2np;

import net.i2p.I2PAppContext;

/**
 * Transmitted from the new outbound endpoint to the creator through a
 * reply tunnel.
 * Variable number of records.
 *
 * @since 0.7.12
 */
public class VariableTunnelBuildReplyMessage extends TunnelBuildReplyMessage {
    public static final int MESSAGE_TYPE = 24;

    /** zero record count, will be set with readMessage() */
    public VariableTunnelBuildReplyMessage(I2PAppContext context) {
        super(context, 0);
    }

    public VariableTunnelBuildReplyMessage(I2PAppContext context, int records) {
        super(context, records);
    }

    @Override
    protected int calculateWrittenLength() { return 1 + super.calculateWrittenLength(); }

    @Override
    public int getType() { return MESSAGE_TYPE; }

    @Override
    public void readMessage(byte[] data, int offset, int dataSize, int type) throws I2NPMessageException {
        // message type will be checked in super()
        int r = data[offset] & 0xff;
        if (r <= 0 || r > MAX_RECORD_COUNT)
            throw new I2NPMessageException("Bad record count " + r);
        RECORD_COUNT = r;
        if (dataSize != calculateWrittenLength())
            throw new I2NPMessageException("Wrong length (expects " + calculateWrittenLength() + ", recv " + dataSize + ")");
        _records = new EncryptedBuildRecord[RECORD_COUNT];
        super.readMessage(data, offset + 1, dataSize, type);
    }

    @Override
    protected int writeMessageBody(byte[] out, int curIndex) throws I2NPMessageException {
        int remaining = out.length - (curIndex + calculateWrittenLength());
        if (remaining < 0)
            throw new I2NPMessageException("Not large enough (too short by " + remaining + ")");
        if (RECORD_COUNT <= 0 || RECORD_COUNT > MAX_RECORD_COUNT)
            throw new I2NPMessageException("Bad record count " + RECORD_COUNT);
        out[curIndex++] = (byte) RECORD_COUNT;
        // can't call super, written length check will fail
        //return super.writeMessageBody(out, curIndex + 1);
        for (int i = 0; i < RECORD_COUNT; i++) {
            System.arraycopy(_records[i].getData(), 0, out, curIndex, RECORD_SIZE);
            curIndex += RECORD_SIZE;
        }
        return curIndex;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("VariableTunnelBuildReplyMessage [ID: ").append(getUniqueId())
           .append("] -> records: ").append(getRecordCount());
        return buf.toString();
    }
}
