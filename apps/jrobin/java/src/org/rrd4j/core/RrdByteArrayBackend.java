package org.rrd4j.core;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Abstract byte array based backend. */
public abstract class RrdByteArrayBackend extends ByteBufferBackend {

    private byte[] buffer;

    /**
     * Path to the RRD file.
     * @param path path to the RRD file
     */
    protected RrdByteArrayBackend(String path) {
        super(path);
    }

    /**
     * Byte array to wrap as backend storage.
     * @param buffer byte array to wrap as backend storage
     */
    protected void setBuffer(byte[] buffer) {
        this.buffer = buffer;
        setByteBuffer(ByteBuffer.wrap(buffer));
    }

    /**
     * Underlying byte buffer.
     * @return the underlying byte buffer
     */
    protected byte[] getBuffer() {
        return buffer;
    }

    /**
     * Read bytes from the in-memory buffer at the given offset.
     *
     * @param offset starting position in the buffer
     * @param bytes array to receive the data
     * @throws java.io.IOException if the read fails
     * @throws java.lang.IllegalArgumentException if offset exceeds buffer length
     */
    @Override
    protected synchronized void read(long offset, byte[] bytes) throws IOException {
        if (offset < 0 || offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Illegal offset: " + offset);
        }

        if (offset + bytes.length <= buffer.length) {
            System.arraycopy(buffer, (int) offset, bytes, 0, bytes.length);
        } else {
            throw new RrdBackendException(
                    "Not enough bytes available in RRD buffer; RRD " + getPath());
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return Number of RRD bytes held in memory.
     */
    public long getLength() {
        return buffer.length;
    }

    /**
     * {@inheritDoc}
     *
     * <p>It will reserves a memory section as a RRD storage.
     */
    protected void setLength(long length) {
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Illegal length: " + length);
        }

        buffer = new byte[(int) length];
        setByteBuffer(ByteBuffer.wrap(buffer));
    }
}
