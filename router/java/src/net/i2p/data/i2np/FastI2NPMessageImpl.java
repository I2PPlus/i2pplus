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
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 *  Ignore, but save, the SHA-256 checksum in the full 16-byte header when read in.
 *  Use the same checksum when writing out.
 *
 *  This is a savings for NTCP in,
 *  and for NTCP-in to NTCP-out for TunnelDataMessages.
 *  It's also a savings for messages embedded in other messages.
 *  Note that SSU does not use the SHA-256 checksum.
 *
 *  Subclasses must take care to set _hasChecksum to false to invalidate it
 *  if the message payload changes between reading and writing.
 *
 *  It isn't clear where, if anywhere, we actually need to send a checksum.
 *  For point-to-point messages over NTCP where we know the router version
 *  of the peer, we could add a method to skip checksum generation.
 *  For end-to-end I2NP messages embedded in a Garlic, TGM, etc...
 *  we would need a flag day.
 *
 *  @since 0.8.12
 */
public abstract class FastI2NPMessageImpl extends I2NPMessageImpl {
    /** One-byte checksum. */
    protected byte _checksum;
    // We skip the fiction that CHECKSUM_LENGTH will ever be anything but 1
    /** Whether the message includes a checksum. */
    protected boolean _hasChecksum;

    public FastI2NPMessageImpl(I2PAppContext context) {
        super(context);
    }

    /**
     *  Ignore, but save, the checksum, to be used later if necessary.
     *
     *  @param maxLen read no more than this many bytes from data starting at offset, even if it is longer
     *                This includes the type byte only if type &lt; 0
     *  @throws IllegalStateException if called twice, to protect saved checksum
     */
    @Override
    public int readBytes(byte[] data, int type, int offset, int maxLen) throws I2NPMessageException {
        if (_hasChecksum)
            throw new IllegalStateException(getClass().getSimpleName() + " read twice");
        int headerSize = HEADER_LENGTH;
        if (type >= 0)
            headerSize--;
        if (maxLen < headerSize)
            throw new I2NPMessageException("Payload is too short " + maxLen);
        int cur = offset;
        if (type < 0) {
            type = data[cur] & 0xff;
            cur++;
        }
        setUniqueId(DataHelper.fromLong(data, cur, 4));
        cur += 4;
        _expiration = DataHelper.fromLong(data, cur, DataHelper.DATE_LENGTH);
        cur += DataHelper.DATE_LENGTH;
        int size = (int)DataHelper.fromLong(data, cur, 2);
        cur += 2;
        _checksum = data[cur];
        cur++;

        if (cur + size > data.length || headerSize + size > maxLen)
            throw new I2NPMessageException("Payload is too short ["
                                           + "data.len=" + data.length
                                           + "maxLen=" + maxLen
                                           + " offset=" + offset
                                           + " cur=" + cur
                                           + " wanted=" + size + "]: " + getClass().getSimpleName());

        int sz = Math.min(size, maxLen - headerSize);
        readMessage(data, cur, sz, type);
        cur += sz;
        _hasChecksum = true;
        return cur - offset;
    }

    /**
     *  If available, use the previously-computed or previously-read checksum for speed
     */
    @Override
    public int toByteArray(byte[] buffer) {
        if (_hasChecksum)
            return toByteArrayWithSavedChecksum(buffer);
        return super.toByteArray(buffer);
    }

    /**
     *  Use a previously-computed checksum for speed
     */
    protected int toByteArrayWithSavedChecksum(byte[] buffer) {
        try {
            int writtenLen = writeMessageBody(buffer, HEADER_LENGTH);
            int payloadLen = writtenLen - HEADER_LENGTH;
            int off = 0;
            buffer[off++] = (byte) getType();
            DataHelper.toLong(buffer, off, 4, getUniqueId());
            off += 4;
            DataHelper.toLong(buffer, off, DataHelper.DATE_LENGTH, _expiration);
            off += DataHelper.DATE_LENGTH;
            DataHelper.toLong(buffer, off, 2, payloadLen);
            off += 2;
            buffer[off] = _checksum;
            return writtenLen;
        } catch (I2NPMessageException ime) {
            _context.logManager().getLog(getClass()).log(Log.CRIT, "Error writing", ime);
            throw new IllegalStateException("Unable to serialize the message " + getClass().getSimpleName(), ime);
        }
    }
}
