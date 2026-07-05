package i2p.susi.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * OutputStream that counts bytes written.
 *
 * @since 0.9.34
 */
public class CountingOutputStream extends FilterOutputStream {

    private long count;

    /**
     * Create a CountingOutputStream wrapping the given output stream.
     *
     * @param out the underlying output stream
     */
    public CountingOutputStream(OutputStream out) {
        super(out);
    }

    /**
     * Get the total number of bytes written so far.
     *
     * @return total bytes written
     */
    public long getWritten() {
        return count;
    }

    /**
     * Write a single byte and count it.
     *
     * @param val the byte to write
     * @throws IOException on I/O error
     */
    @Override
    public void write(int val) throws IOException {
        out.write(val);
        count++;
    }

    /**
     * Write bytes from a buffer and count them.
     *
     * @param src the buffer to write from
     * @param off the start offset in the buffer
     * @param len the number of bytes to write
     * @throws IOException on I/O error
     */
    @Override
    public void write(byte[] src, int off, int len) throws IOException {
        out.write(src, off, len);
        count += len;
    }
}
