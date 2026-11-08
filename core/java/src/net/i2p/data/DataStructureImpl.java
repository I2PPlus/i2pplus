package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA256Generator;
import net.i2p.util.ByteArrayStream;
import net.i2p.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;

/**
 * Base implementation of all data structures.
 *
 */
public abstract class DataStructureImpl implements DataStructure, Serializable {

    /**
     * Buffer capacity used by {@link #toByteArray()} when the serialized size
     * is not known yet, i.e. on the first call. Same size the base
     * implementation has always used, so a one-off serialization costs what it
     * always cost.
     */
    private static final int DEFAULT_BUFFER_SIZE = 512;

    /**
     * Bytes written by the last successful {@link #toByteArray()}, used to size
     * the next call's buffer. A buffer that is filled exactly is handed
     * straight back by {@link ByteArrayStream#toByteArray()} with no copy and
     * no grow-and-recopy, so a structure that is serialized more than once
     * (our RouterInfo on every session-confirmed packet, a Destination on
     * every Base64 rendering) pays for one array instead of three. The size
     * only ever changes the amount of memory allocated, never the bytes
     * written, and a structure that changes size simply grows or trims.
     * <p>
     * Guarded by nothing: a benign race between two threads serializing the
     * same object leaves a stale hint, which costs one copy, not correctness.
     */
    private volatile int _serializedSize;

    /**
     * Constructor for data structure implementations that serialize themselves to a stream.
     */

    public DataStructureImpl() {}


    @Override
    public String toBase64() {
        byte[] data = toByteArray();
        if (data == null) return null;

        return Base64.encode(data);
    }

    @Override
    public void fromBase64(String data) throws DataFormatException {
        if (data == null) throw new DataFormatException("Null data passed in");
        byte[] bytes = Base64.decode(data);
        if (bytes == null) throw new DataFormatException("Invalid Base64: " + data);
        fromByteArray(bytes);
    }

    @Override
    public Hash calculateHash() {
        byte[] data = toByteArray();
        if (data != null) return SHA256Generator.getInstance().calculateHash(data);
        return null;
    }

    @Override
    public byte[] toByteArray() {
        try {
            // Sized from the previous serialization of this object, so the
            // common case fills the buffer exactly and ByteArrayStream returns
            // it as its own array, with no copy out and no grow-and-recopy.
            int size = _serializedSize;
            ByteArrayStream baos = new ByteArrayStream(size > 0 ? size : DEFAULT_BUFFER_SIZE);
            writeBytes(baos);
            int written = baos.size();
            if (written != size) _serializedSize = written;
            return baos.toByteArray();
        } catch (IOException ioe) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
            log.error("Writing the byte array failed", ioe);
            return new byte[0];
        } catch (DataFormatException dfe) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
            log.error("Writing the byte array failed", dfe);
            return new byte[0];
        }
    }

    @Override
    public void fromByteArray(byte[] data) throws DataFormatException {
        if (data == null) throw new DataFormatException("Null data passed in");
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            readBytes(bais);
        } catch (IOException ioe) {
            throw new DataFormatException("Error reading the byte array", ioe);
        }
    }

    /**
     * Repeated reads until buffer is full or IOException is thrown.
     *
     * @param in input stream to read from
     * @param target byte array to read into
     * @return number of bytes read (should always equal target.length)
     * @throws IOException if an I/O error occurs
     */
    protected int read(InputStream in, byte[] target) throws IOException {
        return DataHelper.read(in, target);
    }
}
