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
    private boolean _eofReached;
    private final byte[] _footerLookahead;
    private final int size;
    // Next byte to read.
    private int index;
    private static final InputStream _fakeInputStream = new ByteArrayInputStream(new byte[0]);

    /**
     *  Configure a stream that hides a number of bytes from the reader.
     *  The last n bytes will never be available from read(),
     *  they can only be obtained from getFooter().
     *
     *  initialize() MUST be called before doing any read() calls.
     *
     *  @param lookaheadSize how many bytes to hide
     */
    public LookaheadInputStream(int lookaheadSize) {
        super(_fakeInputStream);
        _footerLookahead = new byte[lookaheadSize];
        size = lookaheadSize;
    }

    public boolean getEOFReached() {
        return _eofReached;
    }

    /**
     *  Start the LookaheadInputStream with the given input stream.
     *  Resets everything if the LookaheadInputStream was previously used.
     *  WARNING - blocking until lookaheadSize bytes are read!
     *
     *  @throws IOException if less than lookaheadSize bytes could be read.
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
        int rv = _footerLookahead[index] & 0xff;
        _footerLookahead[index] = (byte) c;
        index++;
        if (index >= size) index = 0;
        return rv;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (_eofReached) return -1;
        for (int i = 0; i < len; i++) {
            int c = read();
            if (c == -1) {
                if (i == 0) return -1;
                else return i;
            } else {
                buf[off + i] = (byte) c;
            }
        }
        return len;
    }

    /**
     * Grab the lookahead footer.
     * This will be of size lookaheadsize given in constructor.
     * The last byte received will be in the last byte of the array.
     */
    public byte[] getFooter() {
        if (index == 0) return _footerLookahead;
        byte[] rv = new byte[size];
        System.arraycopy(_footerLookahead, index, rv, 0, size - index);
        System.arraycopy(_footerLookahead, 0, rv, size - index, index);
        return rv;
    }

    /**
     *  @since 0.9.33
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
