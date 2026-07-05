package i2p.susi.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.i2p.data.DataHelper;
import net.i2p.util.SecureFileOutputStream;

/**
 * Gzip-compressed file Buffer implementation.
 *
 * @since 0.9.34
 */
public class GzipFileBuffer extends FileBuffer {

	private long _actualLength;
	private CountingInputStream _cis;
	private CountingOutputStream _cos;

	/**
	 * Create a GzipFileBuffer for the entire file.
	 *
	 * @param file the gzip-compressed file
	 */
	public GzipFileBuffer(File file) {
		super(file);
	}

	/**
	 * Create a GzipFileBuffer for a portion of the uncompressed data.
	 *
	 * @param file the gzip-compressed file
	 * @param offset the byte offset to start reading from (uncompressed)
	 * @param sublen the number of bytes to read (uncompressed), or 0 for all
	 */
	public GzipFileBuffer(File file, int offset, int sublen) {
		super(file, offset, sublen);
	}

	/**
	 * Get a GZIP decompressing InputStream, wrapped with a counter.
	 * Caller must call readComplete().
	 *
	 * @return new InputStream chain for reading uncompressed data
	 * @throws IOException on I/O error
	 */
	@Override
	public synchronized InputStream getInputStream() throws IOException {
		if (_cis != null && (_offset <= 0 || _offset == _cis.getRead()))
			return _cis;
		if (_cis != null && _offset > _cis.getRead()) {
			DataHelper.skip(_cis, _offset - _cis.getRead());
			return _cis;
		}
		_cis = new CountingInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(_file))));
		if (_offset > 0)
			DataHelper.skip(_cis, _offset);
		// TODO if _sublen > 0, wrap with a read limiter
		return _cis;
	}

	/**
	 * Get a GZIP compressing OutputStream, wrapped with a counter.
	 * Caller must call writeComplete().
	 *
	 * @return new OutputStream chain for writing compressed data
	 * @throws IllegalStateException if offset > 0 or already open for writing
	 * @throws IOException on I/O error
	 */
	@Override
	public synchronized OutputStream getOutputStream() throws IOException {
		if (_offset > 0)
			throw new IllegalStateException();
		if (_cos != null)
			throw new IllegalStateException();
		_cos = new CountingOutputStream(new BufferedOutputStream(new GZIPOutputStream(new SecureFileOutputStream(_file))));
		return _cos;
	}

	/**
	 * Close the input stream and record the uncompressed length if successful.
	 *
	 * @param success if true, records the bytes read
	 */
	@Override
	public synchronized void readComplete(boolean success) {
		if (_cis != null) {
			if (success)
				_actualLength = _cis.getRead();
			try { _cis.close(); } catch (IOException ioe) { /* ignored */ }
			_cis = null;
		}
	}

	/**
	 * Close the output stream and record the uncompressed length if successful.
	 *
	 * @param success if true, records the bytes written
	 */
	@Override
	public synchronized void writeComplete(boolean success) {
		if (_cos != null) {
			if (success)
				_actualLength = _cos.getWritten();
			try { _cos.close(); } catch (IOException ioe) { /* ignored */ }
			_cos = null;
		}
	}

	/**
	 * Returns the actual uncompressed size.
	 * Only known after reading and calling readComplete(true),
	 * or after writing and calling writeComplete(true),
	 * otherwise returns 0.
	 *
	 * @return the uncompressed length in bytes
	 */
	@Override
	public int getLength() {
		return (int) _actualLength;
	}

	@Override
	public String toString() {
		return "GZFB " + _file;
	}
}
