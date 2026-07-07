/*
 * Created on Nov 15, 2004
 *
 *  This file is part of susimail project, see http://susi.i2p/
 *
 *  Copyright (C) 2004-2005  <susi23@mail.i2p>
 *
 * Licensed under the GPLv2 or later.
 *
 * $Revision: 1.2 $
 */
package i2p.susi.util;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import net.i2p.data.DataHelper;

/**
 * Read-only Buffer for constant data initialized from a byte array.
 * See MemoryBuffer for read/write support.
 *
 * @author susi
 */
public class ReadBuffer implements Buffer {

	public final byte[] content;
	public final int length;
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
	 */
	public int getLength() {
		return length;
	}

	/**
	 * Always valid
	 */
	public int getOffset() {
		return offset;
	}

	@Override
	public String toString()
	{
		return content != null ? DataHelper.getUTF8(content, offset, length) : "";
	}
}
