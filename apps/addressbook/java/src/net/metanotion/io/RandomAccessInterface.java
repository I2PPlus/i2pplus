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
     * @return the file pointer
     */
    public long getFilePointer() throws IOException;
    /** @return the file length */
    public long length() throws IOException;
    /** @see java.io.RandomAccessFile#read() */
    public int read() throws IOException;
    /** @see java.io.RandomAccessFile#read(byte[]) */
    public int read(byte[] b) throws IOException;
    /** @see java.io.RandomAccessFile#read(byte[],int,int) */
    public int read(byte[] b, int off, int len) throws IOException;
    /** @see java.io.RandomAccessFile#seek(long) */
    public void seek(long pos) throws IOException;
    /** @see java.io.RandomAccessFile#setLength(long) */
    public void setLength(long newLength) throws IOException;

/**
     *  Is the file writable? (I2P)
     *  Only valid if the File constructor was used, not the RAF constructor
     *  @since 0.8.8
     */
    public boolean canWrite();

    // Closeable Methods
    /** @see java.io.RandomAccessFile#close() */
    public void close() throws IOException;

    // DataInput Methods
    /** @see java.io.RandomAccessFile#readBoolean() */
    public boolean readBoolean() throws IOException;
    /** @see java.io.RandomAccessFile#readByte() */
    public byte readByte() throws IOException;
    /** @see java.io.RandomAccessFile#readChar() */
    public char readChar() throws IOException;
    /** @see java.io.RandomAccessFile#readDouble() */
    public double readDouble() throws IOException;
    /** @see java.io.RandomAccessFile#readFloat() */
    public float readFloat() throws IOException;
    /** @see java.io.RandomAccessFile#readFully(byte[]) */
    public void readFully(byte[] b) throws IOException;
    /** @see java.io.RandomAccessFile#readFully(byte[],int,int) */
    public void readFully(byte[] b, int off, int len) throws IOException;
    /** @see java.io.RandomAccessFile#readInt() */
    public int readInt() throws IOException;
    /** @see java.io.RandomAccessFile#readLine() */
    public String readLine() throws IOException;
    /** @see java.io.RandomAccessFile#readLong() */
    public long readLong() throws IOException;
    /** @see java.io.RandomAccessFile#readShort() */
    public short readShort() throws IOException;
    /** @see java.io.RandomAccessFile#readUnsignedByte() */
    public int readUnsignedByte() throws IOException;
    /** @see java.io.RandomAccessFile#readUnsignedShort() */
    public int readUnsignedShort() throws IOException;
    // I2P
    /** Read a 4-byte big-endian unsigned int. */
    public int readUnsignedInt() throws IOException;
    /** @see java.io.RandomAccessFile#readUTF() */
    public String readUTF() throws IOException;
    /** @see java.io.RandomAccessFile#skipBytes(int) */
    public int skipBytes(int n) throws IOException;

    // DataOutput Methods
    /** @see java.io.RandomAccessFile#write(int) */
    public void write(int b) throws IOException;
    /** @see java.io.RandomAccessFile#write(byte[]) */
    public void write(byte[] b) throws IOException;
    /** @see java.io.RandomAccessFile#write(byte[],int,int) */
    public void write(byte[] b, int off, int len) throws IOException;
    /** @see java.io.RandomAccessFile#writeBoolean(boolean) */
    public void writeBoolean(boolean v) throws IOException;
    /** @see java.io.RandomAccessFile#writeByte(int) */
    public void writeByte(int v) throws IOException;
    /** @see java.io.RandomAccessFile#writeShort(int) */
    public void writeShort(int v) throws IOException;
    /** @see java.io.RandomAccessFile#writeChar(int) */
    public void writeChar(int v) throws IOException;
    /** @see java.io.RandomAccessFile#writeInt(int) */
    public void writeInt(int v) throws IOException;
    /** @see java.io.RandomAccessFile#writeLong(long) */
    public void writeLong(long v) throws IOException;
    /** @see java.io.RandomAccessFile#writeFloat(float) */
    public void writeFloat(float v) throws IOException;
    /** @see java.io.RandomAccessFile#writeDouble(double) */
    public void writeDouble(double v) throws IOException;
    /** @see java.io.RandomAccessFile#writeBytes(String) */
    public void writeBytes(String s) throws IOException;
    /** @see java.io.RandomAccessFile#writeChars(String) */
    public void writeChars(String s) throws IOException;
    /** @see java.io.RandomAccessFile#writeUTF(String) */
    public void writeUTF(String str) throws IOException;
}
