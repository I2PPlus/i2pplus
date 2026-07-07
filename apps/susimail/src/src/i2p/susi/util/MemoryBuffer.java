package i2p.susi.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Buffer implementation backed by a byte array.
 * Suitable for small amounts of data only.
 *
 * @since 0.9.34
 */
public class MemoryBuffer implements Buffer {

	private ByteArrayOutputStream _baos;
	private byte[] content;
	private final int _size;
	
	/**
	 * Create a MemoryBuffer with default initial capacity.
	 */
	public MemoryBuffer() {
		this(4096);
	}

	/**
	 * Create a MemoryBuffer with specified initial capacity.
	 *
	 * @param size the initial capacity in bytes
	 */
	public MemoryBuffer(int size) {
		_size = size;
	}

	/**
	 * Get an InputStream for the buffered data.
	 *
	 * @return new ByteArrayInputStream
	 * @throws IOException if no data has been written
	 */
	@Override
	public synchronized InputStream getInputStream() throws IOException {
		if (content == null)
			throw new IOException("no data");
		return new ByteArrayInputStream(content);
	}

	/**
	 * Get an OutputStream for writing data to this buffer.
	 *
	 * @return new or existing ByteArrayOutputStream
	 */
	@Override
	public synchronized OutputStream getOutputStream() {
		if (_baos == null)
			_baos = new ByteArrayOutputStream(_size);
		return _baos;
	}

	@Override
	public void readComplete(boolean success) { /* no-op */ }

	/**
	 * Finalize the write. If successful, the data is stored.
	 * If not, the data is discarded.
	 *
	 * @param success if true, stores the data; if false, discards it
	 */
	public synchronized void writeComplete(boolean success) {
		if (success) {
			if (content == null)
				content = _baos.toByteArray();
		} else {
			content = null;
		}
		_baos = null;
	}

	/**
	 * Get the current size of data in this buffer.
	 *
	 * @return the size in bytes
	 */
	@Override
	public synchronized int getLength() {
		if (content != null)
			return content.length;
		if (_baos != null)
			return _baos.size();
		return 0;
	}

	/**
	 * @return 0 always
	 */
	public int getOffset() {
		return 0;
	}

	/**
	 * Get the buffered content after writeComplete(true).
	 *
	 * @return content bytes if writeComplete(true) was called, otherwise null
	 */
	public byte[] getContent() {
		return content;
	}

	@Override
	public String toString() {
		return "MB " + (content == null ? "empty" : content.length + " bytes");
	}
}
