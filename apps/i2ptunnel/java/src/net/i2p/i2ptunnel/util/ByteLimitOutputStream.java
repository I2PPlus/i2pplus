package net.i2p.i2ptunnel.util;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;

/**
 * An OutputStream that limits how many bytes are written, throwing
 * {@link EOFException} when the limit is exceeded.
 *
 * @since 0.9.62
 */
public class ByteLimitOutputStream extends LimitOutputStream {

    private final long _limit;
    private long _count;

    /**
     * Creates a new byte-limited output stream.
     *
     * @param out the underlying output stream
     * @param done callback invoked when the limit is reached
     * @param limit maximum number of bytes allowed, must be greater than zero
     * @throws IllegalArgumentException if limit is not greater than zero
     */
    public ByteLimitOutputStream(OutputStream out, DoneCallback done, long limit) {
        super(out, done);
        if (limit <= 0)
            throw new IllegalArgumentException();
        _limit = limit;
    }

    @Override
    public void write(byte[] src, int off, int len) throws IOException {
        if (len == 0)
            return;
        if (_isDone)
            throw new EOFException("done");
        long togo = _limit - _count;
        boolean last = len >= togo;
        if (last)
            len = (int) togo;
        super.write(src, off, len);
        _count += len;
        if (last)
            setDone();
    }

}
