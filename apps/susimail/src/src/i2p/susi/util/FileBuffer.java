package i2p.susi.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import net.i2p.data.DataHelper;
import net.i2p.util.SecureFileOutputStream;

/**
 * Buffer implementation backed by a file.
 *
 * @since 0.9.34
 */
public class FileBuffer implements Buffer {

	protected final File _file;
	protected final int _offset;
	protected final int _sublen;
	private InputStream _is;
	private OutputStream _os;

	/**
	 * Create a FileBuffer for the entire file.
	 *
	 * @param file the file to buffer
	 */
	public FileBuffer(File file) {
		this(file, 0, 0);
	}

	/**
	 * Create a FileBuffer for a portion of the file.
	 *
	 * @param file the file to buffer
	 * @param offset the byte offset to start reading from
	 * @param sublen the number of bytes to read, or 0 for the entire file
	 */
	public FileBuffer(File file, int offset, int sublen) {
		_file = file;
		_offset = offset;
		_sublen = sublen;
	}

	/**
	 * @return the underlying file
	 */
	public File getFile() {
		return _file;
	}

	/**
         * Get an InputStream for the file, skipping to the offset if needed.
         * Caller must call readComplete().
         *
	 * @return new FileInputStream
	 *  @throws IOException on I/O error
	 */
	public synchronized InputStream getInputStream() throws IOException {
		if (_is != null && _offset <= 0)
			return _is;
		_is = new FileInputStream(_file);
		if (_offset > 0)
			DataHelper.skip(_is, _offset);
		return _is;
	}

	/**
         * Get an OutputStream for the file.
         * Caller must call writeComplete().
         *
	 * @return new SecureFileOutputStream
	 *  @throws IOException on I/O error
	 */
	public synchronized OutputStream getOutputStream() throws IOException {
		if (_os == null)
			_os = new SecureFileOutputStream(_file);
		return _os;
	}

	/**
	 * Close the input stream.
	 *
	 * @param success ignored
	 */
	public synchronized void readComplete(boolean success) {
		if (_is != null) {
			try { _is.close(); } catch (IOException ioe) { /* ignored */ }
			_is = null;
		}
	}

	/**
	 * Close the output stream and delete the file if success is false.
	 *
	 * @param success if false, deletes the file
	 */
	public synchronized void writeComplete(boolean success) {
		if (_os != null) {
			try { _os.close(); } catch (IOException ioe) { /* ignored */ }
			_os = null;
		}
		if (!success)
			_file.delete();
	}

	/**
	 * Get the length of data in this buffer.
	 * Returns the sublen if set, otherwise the file length.
	 *
	 * @return length in bytes
	 */
	public int getLength() {
		if (_sublen > 0)
			return _sublen;
		return (int) _file.length();
	}

	/**
	 * Get the byte offset into the file.
	 *
	 * @return offset in bytes
	 */
	public int getOffset() {
		return _offset;
	}

	@Override
	public String toString() {
		return "FB " + _file;
	}
}
