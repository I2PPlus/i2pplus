package i2p.susi.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Write-only Buffer implementation.
 *
 * @since 0.9.34
 */
public class OutputStreamBuffer implements Buffer {

	private final OutputStream _out;

	/**
	 * Create an OutputStreamBuffer wrapping the given output stream.
	 *
	 * @param out the output stream to write to
	 */
	public OutputStreamBuffer(OutputStream out) {
		_out = out;
	}

	/**
	 * This class only forwards writes to the wrapped stream, so it exposes no
	 * readable data of its own.
	 *
	 * @throws UnsupportedOperationException always, since there is nothing to read
	 */
	@Override
	public InputStream getInputStream() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Stream that collects writes into the buffer.
	 *
	 * @return the same OutputStream that was passed to the constructor
	 */
	@Override
	public OutputStream getOutputStream() {
		return _out;
	}

	/**
	 * Does nothing.
	 */
	@Override
	public void readComplete(boolean success) { /* no-op */ }

	/**
	 * Close the output stream.
	 *
	 * @param success ignored; the stream is closed unconditionally
	 */
	public void writeComplete(boolean success) {
		try { _out.close(); } catch (IOException ioe) { /* ignored */ }
	}

	/**
	 * Bytes written so far.
	 *
	 * @return 0 always
	 */
	public int getLength() {
		return 0;
	}

	/**
	 * Current write position in the array.
	 *
	 * @return 0 always
	 */
	public int getOffset() {
		return 0;
	}

	@Override
	public String toString() {
		return "OSB";
	}
}
