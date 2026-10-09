package net.metanotion.io;
// License: BSD-3-Clause. See docs/LICENSES.md

import java.io.Closeable;
import java.io.IOException;

/**
 * Interface for random access file operations.
 * Provides methods for reading, writing, and seeking within files.
 */
public interface RandomAccessInterface extends Closeable {
    /**
     * Returns the current position of the file pointer.
     * @return the file pointer
     * @throws IOException if the pointer cannot be determined
     */
    public long getFilePointer() throws IOException;
    /**
     * Returns the size of the file in bytes.
     * @return the file length
     * @throws IOException if the length cannot be determined
     */
    public long length() throws IOException;
    /**
     * Reads one byte and advances the file pointer past it.
     * @see java.io.RandomAccessFile#read()
     * @return the byte read, or -1 at the end of file
     * @throws IOException if the file cannot be read
     */
    public int read() throws IOException;
    /**
     * Reads bytes into b and advances the file pointer past them.
     * @see java.io.RandomAccessFile#read(byte[])
     * @param b the buffer to fill
     * @return the number of bytes read, or -1 at the end of file
     * @throws IOException if the file cannot be read
     */
    public int read(byte[] b) throws IOException;
    /**
     * Reads bytes into b at off and advances the file pointer past them.
     * @see java.io.RandomAccessFile#read(byte[],int,int)
     * @param b the buffer to fill
     * @param off the index in b at which to start writing
     * @param len the maximum number of bytes to read
     * @return the number of bytes read, or -1 at the end of file
     * @throws IOException if the file cannot be read
     */
    public int read(byte[] b, int off, int len) throws IOException;
    /**
     * Moves the file pointer to an absolute offset.
     * @see java.io.RandomAccessFile#seek(long)
     * @param pos the absolute offset, in bytes, to position at
     * @throws IOException if pos is negative or the seek fails
     */
    public void seek(long pos) throws IOException;
    /**
     * Truncates or extends the file to the given size.
     * @see java.io.RandomAccessFile#setLength(long)
     * @param newLength the size, in bytes, the file should have
     * @throws IOException if the file cannot be resized
     */
    public void setLength(long newLength) throws IOException;

/**
 * Is the file writable? (I2P)
 * Only valid if the File constructor was used, not the RAF constructor
 * @return true if the file was opened for writing
 * @since 0.8.8
 */
    public boolean canWrite();

    // Closeable Methods
    /** @see java.io.RandomAccessFile#close() */
    public void close() throws IOException;

    // DataInput Methods
    /**
     * Reads a single byte, reporting it as a boolean.
     * @see java.io.RandomAccessFile#readBoolean()
     * @return true if the byte read is nonzero
     * @throws IOException if the file cannot be read
     */
    public boolean readBoolean() throws IOException;
    /**
     * Reads one byte as a signed value.
     * @see java.io.RandomAccessFile#readByte()
     * @return the byte read
     * @throws IOException if the file cannot be read
     */
    public byte readByte() throws IOException;
    /**
     * Reads two bytes as a big-endian char.
     * @see java.io.RandomAccessFile#readChar()
     * @return the char read
     * @throws IOException if the file cannot be read
     */
    public char readChar() throws IOException;
    /**
     * Reads eight bytes as a big-endian double.
     * @see java.io.RandomAccessFile#readDouble()
     * @return the double read
     * @throws IOException if the file cannot be read
     */
    public double readDouble() throws IOException;
    /**
     * Reads four bytes as a big-endian float.
     * @see java.io.RandomAccessFile#readFloat()
     * @return the float read
     * @throws IOException if the file cannot be read
     */
    public float readFloat() throws IOException;
    /**
     * Fills b, failing unless every byte of it is read.
     * @see java.io.RandomAccessFile#readFully(byte[])
     * @param b the buffer to fill
     * @throws IOException if the read fails or the end of file comes first
     */
    public void readFully(byte[] b) throws IOException;
    /**
     * Fills len bytes of b at off, failing unless all are read.
     * @see java.io.RandomAccessFile#readFully(byte[],int,int)
     * @param b the buffer to fill
     * @param off the index in b at which to start writing
     * @param len the number of bytes required
     * @throws IOException if the read fails or the end of file comes first
     */
    public void readFully(byte[] b, int off, int len) throws IOException;
    /**
     * Reads four bytes as a big-endian int.
     * @see java.io.RandomAccessFile#readInt()
     * @return the int read
     * @throws IOException if the file cannot be read
     */
    public int readInt() throws IOException;
    /**
     * Reads bytes up to a line terminator, discarding the terminator.
     * @see java.io.RandomAccessFile#readLine()
     * @return the line read, or null at the end of file
     * @throws IOException if the file cannot be read
     */
    public String readLine() throws IOException;
    /**
     * Reads eight bytes as a big-endian long.
     * @see java.io.RandomAccessFile#readLong()
     * @return the long read
     * @throws IOException if the file cannot be read
     */
    public long readLong() throws IOException;
    /**
     * Reads two bytes as a big-endian short.
     * @see java.io.RandomAccessFile#readShort()
     * @return the short read
     * @throws IOException if the file cannot be read
     */
    public short readShort() throws IOException;
    /**
     * Reads one byte, widening it to an unsigned int.
     * @see java.io.RandomAccessFile#readUnsignedByte()
     * @return the byte read, 0 to 255
     * @throws IOException if the file cannot be read
     */
    public int readUnsignedByte() throws IOException;
    /**
     * Reads two bytes, widening them to an unsigned int.
     * @see java.io.RandomAccessFile#readUnsignedShort()
     * @return the short read, 0 to 65535
     * @throws IOException if the file cannot be read
     */
    public int readUnsignedShort() throws IOException;
    // I2P
    /**
     * Read a 4-byte big-endian unsigned int.
     * @return the value read, which must be non-negative
     * @throws IOException if the value read is negative
     */
    public int readUnsignedInt() throws IOException;
    /**
     * Reads a length-prefixed UTF-8 string.
     * @see java.io.RandomAccessFile#readUTF()
     * @return the decoded string
     * @throws IOException if the length prefix or the string is malformed
     */
    public String readUTF() throws IOException;
    /**
     * Advances the file pointer without reading the bytes skipped.
     * @see java.io.RandomAccessFile#skipBytes(int)
     * @param n the number of bytes to skip
     * @return the number of bytes actually skipped
     * @throws IOException if the file cannot be read
     */
    public int skipBytes(int n) throws IOException;

    // DataOutput Methods
    /**
     * Writes the low eight bits of b.
     * @see java.io.RandomAccessFile#write(int)
     * @param b the value to write, of which only the low byte is used
     * @throws IOException if the file cannot be written
     */
    public void write(int b) throws IOException;
    /**
     * Writes every byte of b.
     * @see java.io.RandomAccessFile#write(byte[])
     * @param b the bytes to write
     * @throws IOException if the file cannot be written
     */
    public void write(byte[] b) throws IOException;
    /**
     * Writes len bytes from b, starting at off.
     * @see java.io.RandomAccessFile#write(byte[],int,int)
     * @param b the buffer the bytes are taken from
     * @param off the index in b at which to start reading
     * @param len the number of bytes to write
     * @throws IOException if the file cannot be written
     */
    public void write(byte[] b, int off, int len) throws IOException;
    /**
     * Writes a single byte holding 1 for true, 0 for false.
     * @see java.io.RandomAccessFile#writeBoolean(boolean)
     * @param v the boolean to write
     * @throws IOException if the file cannot be written
     */
    public void writeBoolean(boolean v) throws IOException;
    /**
     * Writes the low eight bits of v.
     * @see java.io.RandomAccessFile#writeByte(int)
     * @param v the value to write, truncated to eight bits
     * @throws IOException if the file cannot be written
     */
    public void writeByte(int v) throws IOException;
    /**
     * Writes the low sixteen bits of v as a big-endian short.
     * @see java.io.RandomAccessFile#writeShort(int)
     * @param v the value to write, truncated to sixteen bits
     * @throws IOException if the file cannot be written
     */
    public void writeShort(int v) throws IOException;
    /**
     * Writes the low sixteen bits of v as a big-endian char.
     * @see java.io.RandomAccessFile#writeChar(int)
     * @param v the value to write, truncated to sixteen bits
     * @throws IOException if the file cannot be written
     */
    public void writeChar(int v) throws IOException;
    /**
     * Writes v as four big-endian bytes.
     * @see java.io.RandomAccessFile#writeInt(int)
     * @param v the value to write
     * @throws IOException if the file cannot be written
     */
    public void writeInt(int v) throws IOException;
    /**
     * Writes v as eight big-endian bytes.
     * @see java.io.RandomAccessFile#writeLong(long)
     * @param v the value to write
     * @throws IOException if the file cannot be written
     */
    public void writeLong(long v) throws IOException;
    /**
     * Writes v as four big-endian bytes.
     * @see java.io.RandomAccessFile#writeFloat(float)
     * @param v the value to write
     * @throws IOException if the file cannot be written
     */
    public void writeFloat(float v) throws IOException;
    /**
     * Writes v as eight big-endian bytes.
     * @see java.io.RandomAccessFile#writeDouble(double)
     * @param v the value to write
     * @throws IOException if the file cannot be written
     */
    public void writeDouble(double v) throws IOException;
    /**
     * Writes the low byte of each character of s.
     * @see java.io.RandomAccessFile#writeBytes(String)
     * @param s the string whose characters are written, one byte each
     * @throws IOException if the file cannot be written
     */
    public void writeBytes(String s) throws IOException;
    /**
     * Writes each character of s as two big-endian bytes.
     * @see java.io.RandomAccessFile#writeChars(String)
     * @param s the string to write
     * @throws IOException if the file cannot be written
     */
    public void writeChars(String s) throws IOException;
    /**
     * Writes str as a length-prefixed UTF-8 string.
     * @see java.io.RandomAccessFile#writeUTF(String)
     * @param str the string to write
     * @throws IOException if str is too long to encode
     */
    public void writeUTF(String str) throws IOException;
}
