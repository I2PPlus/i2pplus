package net.i2p.data.i2np;

import net.i2p.I2PAppContext;

/**
 * Variable size, small records.
 * Preliminary, see proposal 157.
 *
 * @since 0.9.49
 */
public class ShortTunnelBuildMessage extends TunnelBuildMessage {
    /**
     * MESSAGE_TYPE.
     */
    public static final int MESSAGE_TYPE = 25;
    /**
     * SHORT_RECORD_SIZE.
     */
    public static final int SHORT_RECORD_SIZE = 218;

    /** Zero record count, will be set with readMessage(). */
    public ShortTunnelBuildMessage(I2PAppContext context) {
        super(context, 0);
    }

    /**
     * ShortTunnelBuildMessage.
     */
    public ShortTunnelBuildMessage(I2PAppContext context, int records) {
        super(context, records);
    }

    /**
     *  Store a build record, checking its length.
     *  @param record must be ShortEncryptedBuildRecord or null
     */
    @Override
    public void setRecord(int index, EncryptedBuildRecord record) {
        if (record != null && record.length() != SHORT_RECORD_SIZE)
            throw new IllegalArgumentException();
        super.setRecord(index, record);
    }

    /**
     *  Written length of the message body: one record count byte plus the records.
     */
    @Override
    protected int calculateWrittenLength() { return 1 + (RECORD_COUNT * SHORT_RECORD_SIZE); }

    /**
     *  I2NP message type of a short tunnel build message.
     *  @return the type
     */
    @Override
    public int getType() { return MESSAGE_TYPE; }

    /**
     *  Read the records from a byte array.
     */
    @Override
    public void readMessage(byte[] data, int offset, int dataSize, int type) throws I2NPMessageException {
        if (type != MESSAGE_TYPE)
            throw new I2NPMessageException("Message type is incorrect for this message");
        int r = data[offset] & 0xff;
        if (r <= 0 || r > MAX_RECORD_COUNT)
            throw new I2NPMessageException("Bad record count " + r);
        RECORD_COUNT = r;
        if (dataSize != calculateWrittenLength())
            throw new I2NPMessageException("Wrong length (expects " + calculateWrittenLength() + ", recv " + dataSize + ")");
        _records = new EncryptedBuildRecord[RECORD_COUNT];
        offset++;
        for (int i = 0; i < RECORD_COUNT; i++) {
            byte[] rec = new byte[SHORT_RECORD_SIZE];
            System.arraycopy(data, offset, rec, 0, SHORT_RECORD_SIZE);
            setRecord(i, new ShortEncryptedBuildRecord(rec));
            offset += SHORT_RECORD_SIZE;
        }
    }

    /**
     *  Write the records to the output array, starting at the given index.
     */
    @Override
    protected int writeMessageBody(byte[] out, int curIndex) throws I2NPMessageException {
        int remaining = out.length - (curIndex + calculateWrittenLength());
        if (remaining < 0)
            throw new I2NPMessageException("Not large enough (too short by " + remaining + ")");
        if (RECORD_COUNT <= 0 || RECORD_COUNT > MAX_RECORD_COUNT)
            throw new I2NPMessageException("Bad record count " + RECORD_COUNT);
        out[curIndex++] = (byte) RECORD_COUNT;
        for (int i = 0; i < RECORD_COUNT; i++) {
            System.arraycopy(_records[i].getData(), 0, out, curIndex, SHORT_RECORD_SIZE);
            curIndex += SHORT_RECORD_SIZE;
        }
        return curIndex;
    }

    /**
     *  String form for debugging, showing the id and record count.
     */
    @Override
    public String toString() {
        return " [MsgID: " + getUniqueId() + "] -> Records: " + getRecordCount();
    }
}
