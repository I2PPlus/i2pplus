package net.i2p.util;

import net.i2p.data.DataHelper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * GZIP implementation per
 * <a href="https://www.rfc-editor.org/rfc/rfc1952">RFC 1952</a>, reusing
 * java's standard CRC32 and Inflater and InflaterInputStream implementations.
 * The main difference is that this implementation allows its state to be
 * reset to initial values, and hence reused, while the standard
 * GZIPInputStream reads the GZIP header from the stream on instantiation.
 */
public class ResettableGZIPInputStream extends InflaterInputStream {
    /** Size of the gzip footer, the crc and the size. */
    private static final int FOOTER_SIZE = 8; // CRC32 + ISIZE

    /** See below for why this is necessary. */
    private final ExtraByteInputStream _extraByteInputStream;

    /** Keep a typesafe copy of this. */
    private final LookaheadInputStream _lookaheadStream;

    /** The checksum of the data read. */
    private final CRC32 _crc32;
    /** A one byte buffer for single reads. */
    private final byte[] _buf1 = new byte[1];
    /** True once the stream is fully read. */
    private boolean _complete;

    /**
     * Build a new GZIP stream without a bound compressed stream.  You need
     * to initialize this with initialize(compressedStream) when you want to
     * decompress a stream.
     */
    public ResettableGZIPInputStream() {
        // compressedStream ->
        //   LookaheadInputStream that removes last 8 bytes ->
        //     ExtraByteInputStream that adds 1 byte ->
        //       InflaterInputStream
        // See below for why this is necessary, and
        // https://bugs.java.com/bugdatabase/view_bug.do?bug_id=4795299
        super(new ExtraByteInputStream(new LookaheadInputStream(FOOTER_SIZE)), new Inflater(true));
        _extraByteInputStream = (ExtraByteInputStream) in;
        _lookaheadStream = (LookaheadInputStream) _extraByteInputStream.getInputStream();
        _crc32 = new CRC32();
    }

    /**
     * Warning - blocking!
     *
     * @param compressedStream the GZIP member to decompress, read from on demand
     * @throws IOException if the GZIP header cannot be read or does not carry the
     *                     magic bytes, compression method and flags this decoder requires
     */
    public ResettableGZIPInputStream(InputStream compressedStream) throws IOException {
        this();
        initialize(compressedStream);
    }

    /**
     * Blocking call to initialize this stream with the data from the given
     * compressed stream.
     *
     * @param compressedStream the GZIP member to decompress, read from on demand
     * @throws IOException if the stream ends inside the GZIP header, or the header
     *                     carries the wrong magic bytes, compression method or extended flags
     */
    public final void initialize(InputStream compressedStream) throws IOException {
        len = 0;
        inf.reset();
        _complete = false;
        _crc32.reset();
        _buf1[0] = 0x0;
        _extraByteInputStream.reset();
        // blocking call to read the footer/lookahead, and use the compressed
        // stream as the source for further lookahead bytes
        _lookaheadStream.initialize(compressedStream);
        // now blocking call to read and verify the GZIP header from the
        // lookahead stream
        verifyHeader();
    }

    @Override
    public int read() throws IOException {
        int read = read(_buf1, 0, 1);
        if (read == -1) return -1;
        return _buf1[0] & 0xff;
    }

    @Override
    public int read(byte[] buf) throws IOException {
        return read(buf, 0, buf.length);
    }

    /**
     * Read up to len bytes of uncompressed data into the given buffer.
     */
    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (_complete) {
            // shortcircuit so the inflater doesn't try to refill
            // with the footer's data (which would fail, causing ZLIB err)
            return -1;
        }
        int read = super.read(buf, off, len);
        if (read == -1) {
            verifyFooter();
            return -1;
        } else {
            _crc32.update(buf, off, read);
            // NO, we can't do use getEOFReached here
            // 1) Just because the lookahead stream has hit EOF doesn't mean
            //    that the inflater has given us all the data yet,
            //    this would cause data loss at the end
            if (inf.finished()) {
                verifyFooter();
                inf.reset(); // so it doesn't complain about missing data...
                _complete = true;
            }
            return read;
        }
    }

    /**
     * Moved from i2ptunnel HTTPResponseOutputStream.InternalGZIPInputStream.
     *
     * @return the total read
     * @since 0.8.9
     */
    public long getTotalRead() {
        try {
            return inf.getBytesRead();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Moved from i2ptunnel HTTPResponseOutputStream.InternalGZIPInputStream.
     *
     * @return the total expanded
     * @since 0.8.9
     */
    public long getTotalExpanded() {
        try {
            return inf.getBytesWritten();
        } catch (RuntimeException e) {
            // possible NPE in some implementations
            return 0;
        }
    }

    /**
     * Moved from i2ptunnel HTTPResponseOutputStream.InternalGZIPInputStream.
     *
     * @return uncompressed bytes still buffered, or 0 if the inflater has been
     * closed
     * @since 0.8.9
     */
    public long getRemaining() {
        try {
            return inf.getRemaining();
        } catch (RuntimeException e) {
            // possible NPE in some implementations
            return 0;
        }
    }

    /**
     * Moved from i2ptunnel HTTPResponseOutputStream.InternalGZIPInputStream.
     *
     * @return true once the whole stream has been inflated, false if the
     * inflater has been closed
     * @since 0.8.9
     */
    public boolean getFinished() {
        try {
            return inf.finished();
        } catch (RuntimeException e) {
            // possible NPE in some implementations
            return true;
        }
    }

    /**
     * Calls super.{@link #close()}. May not be reused after this.
     *
     * @throws IOException if the underlying inflater cannot be shut down
     * @since 0.9.40
     */
    public void destroy() throws IOException {
        close();
        super.close();
    }

    /**
     * Does NOT call super.close(), as it cannot be reused if we do that.
     * Broken before 0.9.20.
     *
     * @since 0.9.20
     */
    @Override
    public void close() throws IOException {
        len = 0;
        inf.reset();
        _complete = false;
        _crc32.reset();
        _buf1[0] = 0x0;
        _extraByteInputStream.close();
    }

    /**
     * Moved from i2ptunnel HTTPResponseOutputStream.InternalGZIPInputStream.
     *
     * @since 0.8.9
     */
    @Override
    public String toString() {
        return "Read: " + getTotalRead() + "; Expanded: " + getTotalExpanded() + "; Remaining: " + getRemaining() + "; Finished: " + getFinished();
    }

    /**
     * Checks the footer of the stream.
     *
     * @throws IOException if the footer does not match
     */
    private void verifyFooter() throws IOException {
        byte[] footer = _lookaheadStream.getFooter();

        long actualSize = inf.getTotalOut();
        long expectedSize = DataHelper.fromLongLE(footer, 4, 4);
        if (expectedSize != actualSize) throw new IOException("gunzip expected " + expectedSize + " bytes, got " + actualSize);

        long actualCRC = _crc32.getValue();
        long expectedCRC = DataHelper.fromLongLE(footer, 0, 4);
        if (expectedCRC != actualCRC) throw new IOException("gunzip CRC fail expected 0x" + Long.toHexString(expectedCRC) + " bytes, got 0x" + Long.toHexString(actualCRC));
    }

    /**
     * Make sure the header is valid, throwing an IOException if its b0rked.
     *
     * @throws IOException if the magic bytes or compression method are wrong,
     *                     or the stream ends mid-header
     */
    private void verifyHeader() throws IOException {
        int c = in.read();
        if (c != 0x1F) throw new IOException("First magic byte was wrong [" + c + "]");
        c = in.read();
        if (c != 0x8B) throw new IOException("Second magic byte was wrong [" + c + "]");
        c = in.read();
        if (c != 0x08) throw new IOException("Compression format is INVALID [" + c + "]");

        int flags = in.read();

        // snag (and ignore) the MTIME
        c = in.read();
        if (c == -1) throw new IOException("EOF on MTIME0 [" + c + "]");
        c = in.read();
        if (c == -1) throw new IOException("EOF on MTIME1 [" + c + "]");
        c = in.read();
        if (c == -1) throw new IOException("EOF on MTIME2 [" + c + "]");
        c = in.read();
        if (c == -1) throw new IOException("EOF on MTIME3 [" + c + "]");

        c = in.read();
        if ((c != 0x00) && (c != 0x02) && (c != 0x04)) throw new IOException("Invalid extended flags [" + c + "]");

        c = in.read(); // ignore creator OS

        // handle flags...
        if (0 != (flags & (1 << 5))) {
            // extra header, read and ignore
            int _len = 0;
            c = in.read();
            if (c == -1) throw new IOException("EOF reading the extra header");
            _len = c;
            c = in.read();
            if (c == -1) throw new IOException("EOF reading the extra header");
            _len += (c << 8);

            // now skip that data
            for (int i = 0; i < _len; i++) {
                c = in.read();
                if (c == -1) throw new IOException("EOF reading the extra header's body");
            }
        }

        if (0 != (flags & (1 << 4))) {
            // ignore the name
            c = in.read();
            while (c != 0) {
                if (c == -1) throw new IOException("EOF reading the name");
                c = in.read();
            }
        }

        if (0 != (flags & (1 << 3))) {
            // ignore the comment
            c = in.read();
            while (c != 0) {
                if (c == -1) throw new IOException("EOF reading the comment");
                c = in.read();
            }
        }

        if (0 != (flags & (1 << 6))) {
            // ignore the header CRC16 (we still check the body CRC32)
            c = in.read();
            if (c == -1) throw new IOException("EOF reading the CRC16");
            c = in.read();
            if (c == -1) throw new IOException("EOF reading the CRC16");
        }
    }

    /**
     * Essentially a SequenceInputStream(in, new ByteArrayInputStream(new byte[1])),
     * except that this is resettable.
     * <p>
     * Unsupported:
     * - available() doesn't include the extra byte
     * - skip() doesn't skip the extra byte
     * <p>
     * Why? otherwise the inflater finished() is wrong when the compressed payload
     * (in between the 10 byte header and the 8 byte footer) is a multiple of 512 bytes,
     * which caused read(buf, off, len) above to fail.
     * Happened every time with 1042 byte compressed router infos, for example.
     * <p>
     * Details:
     * <p>
     * Warning with Inflater nowrap = true:
     * <p>
     * "Note: When using the 'nowrap' option it is also necessary to provide an extra "dummy" byte as input.
     * This is required by the ZLIB native library in order to support certain optimizations."
     * <p>
     * <a href="https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/zip/Inflater.html">Inflater</a>
     * <p>
     * This is for sure:
     * <p>
     * "This is not nearly specific enough to be useful.  Where in the compressed byte array is the
     * extra 'dummy' byte" expected?  What is it to contain? When calling setInput() is the 'len'
     * argument incremented to include the dummy byte or not?"
     * <p>
     * This is useless:
     * <p>
     * <a href="https://web.archive.org/web/2014/http://www.java-forums.org/new-java/38604-decompress-un-gzip-byte.html">java-forums thread</a>
     * <p>
     * This seems to be the definitive answer:
     * <p>
     * "The fix simply involves copying the byte array and tacking a single null byte on to the end."
     * <p>
     * http://code.google.com/p/google-apps-sso-sample/issues/detail?id=8
     *
     * @since 0.8.12
     */
    private static class ExtraByteInputStream extends FilterInputStream {
        /** The dummy byte appended at the end. */
        private static final byte DUMMY = 0;
        /** True once the dummy byte was sent. */
        private boolean _extraSent;

        /**
         * Appends one dummy byte to a stream.
         *
         * @param in the stream to wrap
         */
        public ExtraByteInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            if (_extraSent) return -1;
            int rv = in.read();
            if (rv >= 0) return rv;
            _extraSent = true;
            return DUMMY;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (_extraSent) return -1;
            int rv = in.read(buf, off, len);
            if (rv >= 0) return rv;
            _extraSent = true;
            buf[off] = DUMMY;
            return 1;
        }

        @Override
        public void close() throws IOException {
            _extraSent = false;
            in.close();
        }

        /** Does NOT call in.{@link #reset()}. */
        @Override
        public void reset() {
            _extraSent = false;
        }

        /**
         * The stream to read from.
         *
         * @return the wrapped stream
         */
        public InputStream getInputStream() {
            return in;
        }
    }

}
