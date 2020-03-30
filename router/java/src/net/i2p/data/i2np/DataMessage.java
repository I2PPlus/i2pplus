package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;

/**
 * Defines a message containing arbitrary bytes of data
 * This is what goes in a GarlicClove.
 * It was also previously used for generating test messages.
 *
 * @author jrandom
 */
public class DataMessage extends FastI2NPMessageImpl {
    public final static int MESSAGE_TYPE = 20;
    private byte _data[];

    public DataMessage(I2PAppContext context) {
        super(context);
    }

    public byte[] getData() {
        return _data;
    }

    /**
     *  @throws IllegalStateException if data previously set, to protect saved checksum
     */
    public void setData(byte[] data) {
        if (_data != null)
            throw new IllegalStateException();
        _data = data;
    }

    public int getSize() {
        return _data.length;
    }

    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        int curIndex = offset;
        long size = DataHelper.fromLong(data, curIndex, 4);
        curIndex += 4;
        if (size > MAX_SIZE)
            throw new I2NPMessageException("Message is too big (" + size + " bytes)");
        _data = new byte[(int)size];
        System.arraycopy(data, curIndex, _data, 0, (int)size);
    }

    /** calculate the message body's length (not including the header and footer */
    protected int calculateWrittenLength() {
        if (_data == null)
            return 4;
        else
            return 4 + _data.length;
    }

    /** write the message body to the output array, starting at the given index */
    protected int writeMessageBody(byte out[], int curIndex) {
        if (_data == null) {
            out[curIndex++] = 0x0;
            out[curIndex++] = 0x0;
            out[curIndex++] = 0x0;
            out[curIndex++] = 0x0;
        } else {
            DataHelper.toLong(out, curIndex, 4, _data.length);
            curIndex += 4;
            System.arraycopy(_data, 0, out, curIndex, _data.length);
            curIndex += _data.length;
        }
        return curIndex;
    }

    public int getType() { return MESSAGE_TYPE; }

    @Override
    public int hashCode() {
        return DataHelper.hashCode(_data);
    }

    @Override
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof DataMessage) ) {
            DataMessage msg = (DataMessage)object;
            return DataHelper.eq(_data, msg._data);
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("DataMessage");
        buf.append("\n* Data: ");
        if (_data != null) {
            buf.append(_data.length).append(" bytes");
            if (_data.length <= 64)
                buf.append("\n* Payload: ").append(Base64.encode(_data));
        } else {
            buf.append("null");
        }
        return buf.toString();
    }
}
