package i2p.susi.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * InputStream that counts bytes read and skipped.
 *
 * @since 0.9.34
 */
public class CountingInputStream extends FilterInputStream implements ReadCounter {

    protected long count;

    /**
     * Create a CountingInputStream wrapping the given input stream.
     *
     * @param in the underlying input stream
     */
    public CountingInputStream(InputStream in) {
        super(in);
    }

    /**
     * Skip bytes and count them.
     *
     * @param n number of bytes to skip
     * @return actual number of bytes skipped
     * @throws IOException on I/O error
     */
    @Override
    public long skip(long n) throws IOException {
	long rv = in.skip(n);
        count += rv;
        return rv;
    }

    /**
     * Get the total number of bytes read or skipped so far.
     *
     * @return total bytes read/skipped
     */
    public long getRead() {
        return count;
    }

    /**
     * Read a single byte and count it.
     *
     * @return the byte read, or -1 on end of stream
     * @throws IOException on I/O error
     */
    @Override
    public int read() throws IOException {
        int rv = in.read();
        if (rv >= 0)
            count++;
        return rv;
    }

    /**
     * Read bytes into a buffer and count them.
     *
     * @param buf the buffer to read into
     * @param off the start offset in the buffer
     * @param len the maximum number of bytes to read
     * @return the number of bytes read, or -1 on end of stream
     * @throws IOException on I/O error
     */
    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        int rv = in.read(buf, off, len);
        if (rv > 0)
            count += rv;
        return rv;
    }

}
