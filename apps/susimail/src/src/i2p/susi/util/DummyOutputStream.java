package i2p.susi.util;

import java.io.OutputStream;

/**
 * OutputStream that discards all written data.
 *
 * @since 0.9.34
 */
public class DummyOutputStream extends OutputStream {

    public DummyOutputStream() {
        super();
    }

    /**
     * Discard a single byte.
     *
     * @param val the byte value (ignored)
     */
    public void write(int val) { /* no-op */ }

    /**
     * Discard all bytes in the array.
     *
     * @param src the byte array (ignored)
     */
    @Override
    public void write(byte[] src) { /* no-op */ }

    /**
     * Discard bytes from the array.
     *
     * @param src the byte array (ignored)
     * @param off the start offset (ignored)
     * @param len the number of bytes (ignored)
     */
    @Override
    public void write(byte[] src, int off, int len) { /* no-op */ }

    @Override
    public void flush() { /* no-op */ }

    @Override
    public void close() { /* no-op */ }
}
