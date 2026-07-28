/*
Copyright (c) 2006, Matthew Estes
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

	* Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
	* Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.
	* Neither the name of Metanotion Software nor the names of its
contributors may be used to endorse or promote products derived from this
software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package net.metanotion.io;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Implementation of RandomAccessInterface using RandomAccessFile.
 * Provides file-based random access with DataInput and DataOutput capabilities.
 */
public class RAIFile implements RandomAccessInterface, DataInput, DataOutput {
	private final File f;
	private final RandomAccessFile delegate;
	private final boolean r;
	private final boolean w;

	/**
	 * Constructor.
	 *
	 * @param file the underlying file
	 * @throws FileNotFoundException if the file cannot be opened
	 */
	public RAIFile(RandomAccessFile file) throws FileNotFoundException {
		this.f = null;
		this.delegate = file;
		this.r = true;
		// fake, we don't really know
		this.w = true;
	}

	/** @param read must be true */
	public RAIFile(File file, boolean read, boolean write) throws FileNotFoundException {
		this.f = file;
		this.r = read;
		this.w = write;
		String mode = "";
		if(this.r) { mode += "r"; }
		if(this.w) { mode += "w"; }
		this.delegate = new RandomAccessFile(file, mode);
	}

/**
	 *  Is the file writable? (I2P)
	 *  Only valid if the File constructor was used, not the RAF constructor
	 *  @since 0.8.8
	 */
	public boolean canWrite() {
		return this.w;
	}

/**
	 *  @since 0.8.8
	 */
	@Override
	public String toString() {
		if (this.f != null)
			return this.f.getAbsolutePath();
		return this.delegate.toString();
	}

	/**
	 * @return the file pointer
	 */
	public long getFilePointer() throws IOException { return delegate.getFilePointer(); }
	/** @return the file length */
	public long length() throws IOException { return delegate.length(); }
	/** @see java.io.RandomAccessFile#read() */
	public int read() throws IOException { return delegate.read(); }
	/** @see java.io.RandomAccessFile#read(byte[]) */
	public int read(byte[] b) throws IOException { return delegate.read(b); }
	/** @see java.io.RandomAccessFile#read(byte[],int,int) */
	public int read(byte[] b, int off, int len) throws IOException { return delegate.read(b, off, len); }
	/** @see java.io.RandomAccessFile#seek(long) */
	public void seek(long pos) throws IOException { delegate.seek(pos); }
	/** @see java.io.RandomAccessFile#setLength(long) */
	public void setLength(long newLength) throws IOException { delegate.setLength(newLength); }

	// Closeable Methods
	/** @see java.io.RandomAccessFile#close() */
	public void close() throws IOException { delegate.close(); }

	// DataInput Methods
	/** @see java.io.RandomAccessFile#readBoolean() */
	public boolean readBoolean() throws IOException { return delegate.readBoolean(); }
	/** @see java.io.RandomAccessFile#readByte() */
	public byte readByte() throws IOException { return delegate.readByte(); }
	/** @see java.io.RandomAccessFile#readChar() */
	public char readChar() throws IOException { return delegate.readChar(); }
	/** @see java.io.RandomAccessFile#readDouble() */
	public double readDouble() throws IOException { return delegate.readDouble(); }
	/** @see java.io.RandomAccessFile#readFloat() */
	public float readFloat() throws IOException { return delegate.readFloat(); }
	/** @see java.io.RandomAccessFile#readFully(byte[]) */
	public void readFully(byte[] b) throws IOException { delegate.readFully(b); }
	/** @see java.io.RandomAccessFile#readFully(byte[],int,int) */
	public void readFully(byte[] b, int off, int len) throws IOException { delegate.readFully(b, off, len); }
	/** @see java.io.RandomAccessFile#readInt() */
	public int readInt() throws IOException { return delegate.readInt(); }
	/** @see java.io.RandomAccessFile#readLine() */
	public String readLine() throws IOException { return delegate.readLine(); }
	/** @see java.io.RandomAccessFile#readLong() */
	public long readLong() throws IOException { return delegate.readLong(); }
	/** @see java.io.RandomAccessFile#readShort() */
	public short readShort() throws IOException { return delegate.readShort(); }
	/** @see java.io.RandomAccessFile#readUnsignedByte() */
	public int readUnsignedByte() throws IOException { return delegate.readUnsignedByte(); }
	/** @see java.io.RandomAccessFile#readUnsignedShort() */
	public int readUnsignedShort() throws IOException { return delegate.readUnsignedShort(); }

	/**
	 *  Reads an unsigned 32-bit integer from the file. (I2P)
	 *  @throws IOException if the read value is negative
	 */
	public int readUnsignedInt()  throws IOException {
		int rv = readInt();
		if (rv < 0)
			throw new IOException("Negative value for unsigned int: " + rv);
		return rv;
	}

	/**
	 * Read a UTF encoded string with 4-byte length prefix. (I2P)
	 *
	 * <p>This method uses a 4-byte length prefix instead of Java's standard
	 * 2-byte prefix, allowing for strings up to 16MB in length. The upper byte
	 * must be zero for compatibility.</p>
	 *
	 * @return the UTF-8 decoded string
	 * @throws IOException if the length encoding is invalid or EOF is reached
	 */
	public String readUTF() throws IOException {
		int len = delegate.readInt();
		if((len < 0) || (len >= 16777216)) { throw new IOException("Bad Length Encoding"); }
		byte[] bytes = new byte[len];
		int l = delegate.read(bytes);
		if(l==-1) { throw new IOException("EOF while reading String"); }
		String s = new String(bytes, "UTF-8");
		return s;
	}

	/** @see java.io.RandomAccessFile#skipBytes(int) */
	public int skipBytes(int n) throws IOException { return delegate.skipBytes(n); }

	// DataOutput Methods
	/** @see java.io.RandomAccessFile#write(int) */
	public void write(int b) throws IOException { delegate.write(b); }
	/** @see java.io.RandomAccessFile#write(byte[]) */
	public void write(byte[] b) throws IOException { delegate.write(b); }
	/** @see java.io.RandomAccessFile#write(byte[],int,int) */
	public void write(byte[] b, int off, int len) throws IOException { delegate.write(b, off, len); }
	/** @see java.io.RandomAccessFile#writeBoolean(boolean) */
	public void writeBoolean(boolean v) throws IOException { delegate.writeBoolean(v); }
	/** @see java.io.RandomAccessFile#writeByte(int) */
	public void writeByte(int v) throws IOException { delegate.writeByte(v); }
	/** @see java.io.RandomAccessFile#writeShort(int) */
	public void writeShort(int v) throws IOException { delegate.writeShort(v); }
	/** @see java.io.RandomAccessFile#writeChar(int) */
	public void writeChar(int v) throws IOException { delegate.writeChar(v); }
	/** @see java.io.RandomAccessFile#writeInt(int) */
	public void writeInt(int v) throws IOException { delegate.writeInt(v); }
	/** @see java.io.RandomAccessFile#writeLong(long) */
	public void writeLong(long v) throws IOException { delegate.writeLong(v); }
	/** @see java.io.RandomAccessFile#writeFloat(float) */
	public void writeFloat(float v) throws IOException { delegate.writeFloat(v); }
	/** @see java.io.RandomAccessFile#writeDouble(double) */
	public void writeDouble(double v) throws IOException { delegate.writeDouble(v); }
	/** @see java.io.RandomAccessFile#writeBytes(String) */
	public void writeBytes(String s) throws IOException { delegate.writeBytes(s); }
	/** @see java.io.RandomAccessFile#writeChars(String) */
	public void writeChars(String s) throws IOException { delegate.writeChars(s); }

	/**
	 * Write a UTF encoded string with 4-byte length prefix. (I2P)
	 *
	 * <p>This method uses a 4-byte length prefix instead of Java's standard
	 * 2-byte prefix, allowing for strings up to 16MB in length.</p>
	 *
	 * @param str the string to write
	 * @throws IOException if the string is too long for encoding
	 */
	public void writeUTF(String str) throws IOException {
		byte[] string = str.getBytes("UTF-8");
		if(string.length >= 16777216) { throw new IOException("String to long for encoding type"); }
		delegate.writeInt(string.length);
		delegate.write(string);
	}
}
