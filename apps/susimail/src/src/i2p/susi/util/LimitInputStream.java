package i2p.susi.util;

import java.io.IOException;
import java.io.InputStream;

/**
 * InputStream that limits total bytes read to a specified maximum.
 *
 * @since 0.9.34
 */
public class LimitInputStream extends CountingInputStream {

    private final long maxx;

    /**
     * Create a LimitInputStream that limits total bytes read.
     *
     * @param in the underlying input stream
     * @param max the maximum number of bytes that may be read
     */
    public LimitInputStream(InputStream in, long max) {
        super(in);
        if (max < 0)
            throw new IllegalArgumentException("negative limit: " + max);
        maxx = max;
    }

    /**
     * Return the number of bytes available, limited by the remaining quota.
     *
     * @return bytes available
     * @throws IOException on I/O error
     */
    @Override
    public int available() throws IOException {
        return (int) Math.min(maxx - count, super.available());
    }

    /**
     * Skip bytes, limited by the remaining quota.
     *
     * @param n number of bytes to skip
     * @return actual bytes skipped
     * @throws IOException on I/O error
     */
    @Override
    public long skip(long n) throws IOException {
        return super.skip(Math.min(maxx - count, n));
    }

    /**
     * Read a single byte, returning -1 if the limit has been reached.
     *
     * @return the byte read, or -1 on end of stream or limit reached
     * @throws IOException on I/O error
     */
    @Override
    public int read() throws IOException {
        if (count >= maxx)
            return -1;
        return super.read();
    }

    /**
     * Read bytes into a buffer, limited by the remaining quota.
     *
     * @param buf the buffer to read into
     * @param off the start offset in the buffer
     * @param len the maximum number of bytes to read
     * @return the number of bytes read, or -1 on end of stream or limit reached
     * @throws IOException on I/O error
     */
    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (count >= maxx)
            return -1;
        return super.read(buf, off, (int) Math.min(maxx - count, len));
    }
}
