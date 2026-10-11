// License: GPLv2+. See docs/LICENSES.md
package i2p.susi.util;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import net.i2p.data.DataHelper;

/**
 * Read-only Buffer for constant data initialized from a byte array.
 * See MemoryBuffer for read/write support.
 *
 */
public class ReadBuffer implements Buffer {

    /**
     * The backing byte array the window is read from.
     */
    public final byte[] content;
    /**
     * The number of readable bytes in the window.
     */
    public final int length;
    /**
     * The index within the backing array where the window starts.
     */
    public final int offset;

    /**
     * Create a ReadBuffer for a portion of a byte array.
     *
     * @param content the byte array containing the data
     * @param offset the start offset within the array
     * @param length the number of bytes from offset
     */
    public ReadBuffer(byte[] content, int offset, int length) {
        this.content = content;
        this.offset = offset;
        this.length = length;
    }

    /**
     * @return new ByteArrayInputStream over the content
     * @since 0.9.34
     */
    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(content, offset, length);
    }

    /**
     * @return the output stream
     * @throws IllegalStateException always
     * @since 0.9.34
     */
    @Override
    public OutputStream getOutputStream() {
        throw new IllegalStateException();
    }

    /**
     * Does nothing
     * @since 0.9.34
     */
    @Override
    public void readComplete(boolean success) { /* no-op */ }

    /**
     * Does nothing
     * @since 0.9.34
     */
    public void writeComplete(boolean success) { /* no-op */ }

    /**
     * Always valid
     * @return the length
     */
    public int getLength() {
        return length;
    }

    /**
     * Always valid
     * @return the offset
     */
    public int getOffset() {
        return offset;
    }

    /**
     * Decodes the window as UTF-8, or returns an empty string when there is no backing array.
     */
    @Override
    public String toString()
    {
        return content != null ? DataHelper.getUTF8(content, offset, length) : "";
    }
}
