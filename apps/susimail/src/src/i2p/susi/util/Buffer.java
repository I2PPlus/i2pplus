package i2p.susi.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Base interface for data buffers.
 * Data access is primarily through streams, with additional methods
 * available in subclasses.
 *
 * @since 0.9.34
 */
public interface Buffer {

	/**
	 *  Get an InputStream for reading from this buffer.
	 *
	 *  @return InputStream for reading
	 *  @throws IOException on I/O error
	 */
	public InputStream getInputStream() throws IOException;

	/**
	 *  Get an OutputStream for writing to this buffer.
	 *
	 *  @return OutputStream for writing
	 *  @throws IOException on I/O error
	 */
	public OutputStream getOutputStream() throws IOException;

	/**
	 *  Top-level reader MUST call this to close the input stream.
	 *
	 *  @param success if false, the read is considered failed
	 */
	public void readComplete(boolean success);

	/**
	 *  Writer MUST call this when done.
	 *
	 *  @param success if false, deletes any resources
	 */
	public void writeComplete(boolean success);

	/**
	 *  Get the length of data in this buffer.
	 *
	 *  @return length in bytes
	 */
	public int getLength();

	/**
	 *  Get the offset into the underlying data.
	 *
	 *  @return offset in bytes
	 */
	public int getOffset();
}
