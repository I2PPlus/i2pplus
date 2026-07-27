package net.i2p.data.i2np;

import net.i2p.I2PAppContext;

/**
 * Variable number of records.
 *
 * @since 0.7.12
 */
public class VariableTunnelBuildMessage extends TunnelBuildMessage {
    /** Message type ID for this I2NP message */
    public static final int MESSAGE_TYPE = 23;

    /** zero record count, will be set with readMessage() */
    public VariableTunnelBuildMessage(I2PAppContext context) {
        super(context, 0);
    }
    /** Constructor with the given number of build records. */

    public VariableTunnelBuildMessage(I2PAppContext context, int records) {
        super(context, records);
    }

    /**
     * Length including record count byte plus all encrypted records.
     */
    @Override
    protected int calculateWrittenLength() { return 1 + super.calculateWrittenLength(); }

    /**
     * I2NP message type 23 for variable-record tunnel build requests.
     */
    @Override
    public int getType() { return MESSAGE_TYPE; }

    /**
     * Parse record count from first byte, then delegate to parent for record data.
     */
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

    /**
     * Write record count byte followed by all encrypted build records.
     */
    @Override
    protected int writeMessageBody(byte[] out, int curIndex) throws I2NPMessageException {
        int remaining = out.length - (curIndex + calculateWrittenLength());
        if (remaining < 0)
            throw new I2NPMessageException("Not large enough (too short by " + remaining + ")");
        if (RECORD_COUNT <= 0 || RECORD_COUNT > MAX_RECORD_COUNT)
            throw new I2NPMessageException("Bad record count " + RECORD_COUNT);
        out[curIndex++] = (byte) RECORD_COUNT;
        // can't call super, written length check will fail
        for (int i = 0; i < RECORD_COUNT; i++) {
            System.arraycopy(_records[i].getData(), 0, out, curIndex, RECORD_SIZE);
            curIndex += RECORD_SIZE;
        }
        return curIndex;
    }

    /**
     * Label with unique ID and record count for logging.
     */
    @Override
    public String toString() {
        return "VariableTunnelBuildMessage [ID: " + getUniqueId() + "] -> " + getRecordCount() + " records";
    }
}
