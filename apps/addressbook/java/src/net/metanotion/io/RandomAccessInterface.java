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

import java.io.Closeable;
import java.io.IOException;

/**
 * Interface for random access file operations.
 * Provides methods for reading, writing, and seeking within files.
 */
public interface RandomAccessInterface extends Closeable {
	/**
	 * @return the file pointer
	 */
	public long getFilePointer() throws IOException;
	/**
	 * length.
	 */
	public long length() throws IOException;
	/**
	 * read.
	 */
	public int read() throws IOException;
	/**
	 * read.
	 */
	public int read(byte[] b) throws IOException;
	/**
	 * read.
	 */
	public int read(byte[] b, int off, int len) throws IOException;
	/**
	 * seek.
	 */
	public void seek(long pos) throws IOException;
	/**
	 * setLength.
	 */
	public void setLength(long newLength) throws IOException;

/**
	 *  Is the file writable? (I2P)
	 *  Only valid if the File constructor was used, not the RAF constructor
	 *  @since 0.8.8
	 */
	public boolean canWrite();

	// Closeable Methods
	/**
	 * close.
	 */
	public void close() throws IOException;

	// DataInput Methods
	/**
	 * readBoolean.
	 */
	public boolean readBoolean() throws IOException;
	/**
	 * readByte.
	 */
	public byte readByte() throws IOException;
	/**
	 * readChar.
	 */
	public char readChar() throws IOException;
	/**
	 * readDouble.
	 */
	public double readDouble() throws IOException;
	/**
	 * readFloat.
	 */
	public float readFloat() throws IOException;
	/**
	 * readFully.
	 */
	public void readFully(byte[] b) throws IOException;
	/**
	 * readFully.
	 */
	public void readFully(byte[] b, int off, int len) throws IOException;
	/**
	 * readInt.
	 */
	public int readInt() throws IOException;
	/**
	 * readLine.
	 */
	public String readLine() throws IOException;
	/**
	 * readLong.
	 */
	public long readLong() throws IOException;
	/**
	 * readShort.
	 */
	public short readShort() throws IOException;
	/**
	 * readUnsignedByte.
	 */
	public int readUnsignedByte() throws IOException;
	/**
	 * readUnsignedShort.
	 */
	public int readUnsignedShort() throws IOException;
	// I2P
	/**
	 * readUnsignedInt.
	 */
	public int readUnsignedInt() throws IOException;
	/**
	 * readUTF.
	 */
	public String readUTF() throws IOException;
	/**
	 * skipBytes.
	 */
	public int skipBytes(int n) throws IOException;

	// DataOutput Methods
	/**
	 * write.
	 */
	public void write(int b) throws IOException;
	/**
	 * write.
	 */
	public void write(byte[] b) throws IOException;
	/**
	 * write.
	 */
	public void write(byte[] b, int off, int len) throws IOException;
	/**
	 * writeBoolean.
	 */
	public void writeBoolean(boolean v) throws IOException;
	/**
	 * writeByte.
	 */
	public void writeByte(int v) throws IOException;
	/**
	 * writeShort.
	 */
	public void writeShort(int v) throws IOException;
	/**
	 * writeChar.
	 */
	public void writeChar(int v) throws IOException;
	/**
	 * writeInt.
	 */
	public void writeInt(int v) throws IOException;
	/**
	 * writeLong.
	 */
	public void writeLong(long v) throws IOException;
	/**
	 * writeFloat.
	 */
	public void writeFloat(float v) throws IOException;
	/**
	 * writeDouble.
	 */
	public void writeDouble(double v) throws IOException;
	/**
	 * writeBytes.
	 */
	public void writeBytes(String s) throws IOException;
	/**
	 * writeChars.
	 */
	public void writeChars(String s) throws IOException;
	/**
	 * writeUTF.
	 */
	public void writeUTF(String str) throws IOException;
}
