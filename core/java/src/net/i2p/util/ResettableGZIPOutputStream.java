package net.i2p.util;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * GZIP implementation per
 * <a href="http://www.faqs.org/rfcs/rfc1952.html">RFC 1952</a>, reusing
 * java's standard CRC32 and Deflater implementations.  The main difference
 * is that this implementation allows its state to be reset to initial
 * values, and hence reused, while the standard GZIPOutputStream writes the
 * GZIP header to the stream on instantiation, rather than on first write.
 *
 */
public class ResettableGZIPOutputStream extends DeflaterOutputStream {
    /** Has the header been written out yet? */
    private boolean _headerWritten;

    private boolean _footerWritten;

    /** How much data is in the uncompressed stream? */
    private long _writtenSize;

    private final CRC32 _crc32;
    private static final boolean DEBUG = false;

    /**
     * Stream writing gzip data to the given output stream.
     *
     * @param o the underlying output stream
     */
    public ResettableGZIPOutputStream(OutputStream o) {
        super(o, new Deflater(9, true));
        _crc32 = new CRC32();
    }

    /**
     * Reinitialze everything so we can write a brand new gzip output stream
     * again.
     */
    public void reset() {
        if (DEBUG) System.out.println("Resetting (writtenSize=" + _writtenSize + ")");
        def.reset();
        _crc32.reset();
        _writtenSize = 0;
        _headerWritten = false;
        _footerWritten = false;
    }

    private static final byte[] HEADER = new byte[] {
        (byte) 0x1F,
        (byte) 0x8b, // magic bytes
        0x08, // compression format == DEFLATE
        0x00, // flags (NOT using CRC16, filename, etc)
        0x00,
        0x00,
        0x00,
        0x00, // no modification time available (don't leak this!)
        0x02, // maximum compression
        (byte) 0xFF // unknown creator OS (!!!)
    };

    /**
     * Obviously not threadsafe, but it's a stream, that's standard.
     */
    private void ensureHeaderIsWritten() throws IOException {
        if (_headerWritten) return;
        if (DEBUG) System.out.println("Writing header");
        out.write(HEADER);
        _headerWritten = true;
    }

    private void writeFooter() throws IOException {
        if (_footerWritten) return;
        // damn RFC writing their bytes backwards...
        long crcVal = _crc32.getValue();
        out.write((int) (crcVal & 0xFF));
        out.write((int) ((crcVal >>> 8) & 0xFF));
        out.write((int) ((crcVal >>> 16) & 0xFF));
        out.write((int) ((crcVal >>> 24) & 0xFF));

        long sizeVal = _writtenSize; // % (1 << 31) // *redundant*
        out.write((int) (sizeVal & 0xFF));
        out.write((int) ((sizeVal >>> 8) & 0xFF));
        out.write((int) ((sizeVal >>> 16) & 0xFF));
        out.write((int) ((sizeVal >>> 24) & 0xFF));
        out.flush();
        if (DEBUG) {
            System.out.println("Footer written: crcVal=" + crcVal + " sizeVal=" + sizeVal + " written=" + _writtenSize);
            System.out.println("size hex: " + Long.toHexString(sizeVal));
            System.out.print("size2 hex:" + Long.toHexString((int) (sizeVal & 0xFF)));
            System.out.print(Long.toHexString((int) ((sizeVal >>> 8) & 0xFF)));
            System.out.print(Long.toHexString((int) ((sizeVal >>> 16) & 0xFF)));
            System.out.print(Long.toHexString((int) ((sizeVal >>> 24) & 0xFF)));
            System.out.println();
        }
        _footerWritten = true;
    }

    /**
     *  Calls super.close(). May not be reused after this.
     *
     *  @since 0.9.40
     */
    public void destroy() throws IOException {
        def.end();
        super.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        finish();
        super.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void finish() throws IOException {
        ensureHeaderIsWritten();
        super.finish();
        writeFooter();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(int b) throws IOException {
        ensureHeaderIsWritten();
        _crc32.update(b);
        _writtenSize++;
        super.write(b);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(byte[] buf) throws IOException {
        write(buf, 0, buf.length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
        ensureHeaderIsWritten();
        _crc32.update(buf, off, len);
        _writtenSize += len;
        super.write(buf, off, len);
    }

}
