package net.i2p.util;

import net.i2p.data.DataHelper;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Simple lookahead buffer to keep the last K bytes in reserve,
 * configured to easily be reused.  Currently only used by the
 * ResettableGZIPInputStream.
 */
public class LookaheadInputStream extends FilterInputStream {
    /** True once the footer has been seen. */
    private boolean _eofReached;
        /** The footer bytes to watch for. */
    private final byte[] _footerLookahead;
    /** Scratch space for moving the trailing bytes during an in-place bulk read. */
    private final byte[] _readAhead;
    /** Length of the footer to look ahead for. */
    private final int size;
    /** Next footer byte to read. */
    private int index;
    /** Empty stream returned once the footer has been reached. */
    private static final InputStream _fakeInputStream = new ByteArrayInputStream(new byte[0]);

    /**
     * Configure a stream that hides a number of bytes from the reader.
     * The last n bytes will never be available from {@link #read()},
     * they can only be obtained from {@link #getFooter()}.
     * <p>
     * {@link #initialize} MUST be called before doing any read() calls.
     *
     * @param lookaheadSize how many bytes to hide
     */
    public LookaheadInputStream(int lookaheadSize) {
        super(_fakeInputStream);
        _footerLookahead = new byte[lookaheadSize];
        _readAhead = new byte[lookaheadSize];
        size = lookaheadSize;
    }

    /**
     * Whether the end of the stream has been reached.
     * @return whether EOF has been reached
     */
    public boolean getEOFReached() {
        return _eofReached;
    }

    /**
     * Start the LookaheadInputStream with the given input stream.
     * Resets everything if the LookaheadInputStream was previously used.
     * WARNING - blocking until lookaheadSize bytes are read!
     *
     * @param src the input stream
     * @throws IOException if less than lookaheadSize bytes could be read.
     */
    public void initialize(InputStream src) throws IOException {
        in = src;
        _eofReached = false;
        index = 0;
        DataHelper.read(in, _footerLookahead);
    }

    @Override
    public int read() throws IOException {
        if (_eofReached) return -1;
        int c = in.read();
        if (c == -1) {
            _eofReached = true;
            return -1;
        }
        if (size == 0)
            return c;
        int rv = _footerLookahead[index] & 0xff;
        _footerLookahead[index] = (byte) c;
        index++;
        if (index >= size) index = 0;
        return rv;
    }

    /**
     * Reads into the caller's array in one pass instead of one byte at a time,
     * then rotates the footer out of the way in place. The count returned is what
     * the underlying stream returned, which may be fewer than {@code len} - legal
     * for an InputStream, and the only caller, the gzip stream in this package,
     * loops on it.
     *
     * @param buf the array to read into
     * @param off where in the array to start
     * @param len how many bytes to ask for
     * @return the number of bytes read, or -1 at EOF
     * @throws IOException if the underlying stream fails
     * @throws NullPointerException if buf is null
     * @throws IndexOutOfBoundsException if the range does not fit in buf
     * @since 0.9.72+ bulk reads
     */
    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (buf == null)
            throw new NullPointerException();
        // offset > buf.length - len rather than off + len > buf.length, which
        // overflows for a huge len
        if (off < 0 || len < 0 || off > buf.length - len)
            throw new IndexOutOfBoundsException();
        if (len == 0)
            return 0;
        if (_eofReached) return -1;
        int count = in.read(buf, off, len);
        if (count < 0) {
            _eofReached = true;
            return -1;
        }
        if (count == 0 || size == 0)
            return count;

        if (count >= size) {
            // Save the new footer before shifting the newly read data right.
            System.arraycopy(buf, off + count - size, _readAhead, 0, size);
            System.arraycopy(buf, off, buf, off + size, count - size);
            int first = size - index;
            System.arraycopy(_footerLookahead, index, buf, off, first);
            System.arraycopy(_footerLookahead, 0, buf, off + first, index);
            System.arraycopy(_readAhead, 0, _footerLookahead, 0, size);
            index = 0;
        } else {
            // A short read replaces only part of the circular footer buffer.
            System.arraycopy(buf, off, _readAhead, 0, count);
            int first = Math.min(count, size - index);
            System.arraycopy(_footerLookahead, index, buf, off, first);
            System.arraycopy(_footerLookahead, 0, buf, off + first, count - first);
            System.arraycopy(_readAhead, 0, _footerLookahead, index, first);
            System.arraycopy(_readAhead, first, _footerLookahead, 0, count - first);
            index += count;
            if (index >= size)
                index -= size;
        }
        return count;
    }

    /**
     * Grab the lookahead footer.
     * This will be of size lookaheadsize given in constructor.
     * The last byte received will be in the last byte of the array.
     *
     * @return the lookahead footer bytes
     */
    public byte[] getFooter() {
        if (index == 0) return _footerLookahead;
        byte[] rv = new byte[size];
        System.arraycopy(_footerLookahead, index, rv, 0, size - index);
        System.arraycopy(_footerLookahead, 0, rv, size - index, index);
        return rv;
    }

    /**
     * Skip forward, respecting the lookahead buffer.
     *
     * @since 0.9.33
     */
    @Override
    public long skip(long n) throws IOException {
        long rv = 0;
        int c; // NOSONAR S1481
        while (rv < n && (c = read()) >= 0) {
            rv++;
        }
        return rv;
    }

}
