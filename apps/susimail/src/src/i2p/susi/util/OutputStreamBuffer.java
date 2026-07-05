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
	 * @throws UnsupportedOperationException
	 */
	@Override
	public InputStream getInputStream() {
		throw new UnsupportedOperationException();
	}

	/**
	 * @return the OutputStream
	 */
	@Override
	public OutputStream getOutputStream() {
		return _out;
	}

	/**
	 * Does nothing
	 */
	@Override
	public void readComplete(boolean success) {}

	/**
	 * Close the output stream.
	 *
	 * @param success ignored
	 */
	public void writeComplete(boolean success) {
		try { _out.close(); } catch (IOException ioe) { /* ignored */ }
	}

	/**
	 * @return 0 always
	 */
	public int getLength() {
		return 0;
	}

	/**
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
