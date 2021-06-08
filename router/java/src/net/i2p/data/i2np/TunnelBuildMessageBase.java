package net.i2p.data.i2np;

import net.i2p.I2PAppContext;

/**
 *  Base for TBM, TBRM, VTBM, VTBRM
 *  Retrofitted over them.
 *  There's really no difference between the build and build reply.
 *
 *  TBM and VBTM (but not TBRM and VTBRM?) messages are modified
 *  in-place by doing a single setRecord(), and retransmitted.
 *  Therefore they are NOT good candidates to use FastI2NPMessageImpl;
 *  the checksum would have to be invalidated with every setRecord().
 *  Which we could do in TBM and VTBM but not TBRM and VTBRM,
 *  but keep it simple for now.
 *
 *  @since 0.8.8
 */
public abstract class TunnelBuildMessageBase extends I2NPMessageImpl {
    protected EncryptedBuildRecord _records[];
    protected int RECORD_COUNT;
    public static final int MAX_RECORD_COUNT = 8;
    
    public TunnelBuildMessageBase(I2PAppContext context) {
        this(context, MAX_RECORD_COUNT);
    }

    /** @since 0.7.12 */
    protected TunnelBuildMessageBase(I2PAppContext context, int records) {
        super(context);
        if (records > 0) {
            RECORD_COUNT = records;
            _records = new EncryptedBuildRecord[records];
        }
        // else will be initialized by readMessage()
    }

    /**
     *  @param record may be null
     */
    public void setRecord(int index, EncryptedBuildRecord record) { _records[index] = record; }

    /**
     *  @return may be null
     */
    public EncryptedBuildRecord getRecord(int index) { return _records[index]; }

    /** @since 0.7.12 */
    public int getRecordCount() { return RECORD_COUNT; }
    
    public static final int RECORD_SIZE = 512+16;
    
    protected int calculateWrittenLength() { return RECORD_SIZE * RECORD_COUNT; }

    public void readMessage(byte[] data, int offset, int dataSize, int type) throws I2NPMessageException {
        if (type != getType()) 
            throw new I2NPMessageException("Message type is incorrect for this message");
        if (dataSize != calculateWrittenLength()) 
            throw new I2NPMessageException("Wrong length (expects " + calculateWrittenLength() + ", recv " + dataSize + ")");
        
        for (int i = 0; i < RECORD_COUNT; i++) {
            int off = offset + (i * RECORD_SIZE);
            byte rec[] = new byte[RECORD_SIZE];
            System.arraycopy(data, off, rec, 0, RECORD_SIZE);
            setRecord(i, new EncryptedBuildRecord(rec));
        }
    }
    
    protected int writeMessageBody(byte[] out, int curIndex) throws I2NPMessageException {
        int remaining = out.length - (curIndex + calculateWrittenLength());
        if (remaining < 0)
            throw new I2NPMessageException("Not large enough (too short by " + remaining + ")");
        for (int i = 0; i < RECORD_COUNT; i++) {
            System.arraycopy(_records[i].getData(), 0, out, curIndex, RECORD_SIZE);
            curIndex += RECORD_SIZE;
        }
        return curIndex;
    }
}
